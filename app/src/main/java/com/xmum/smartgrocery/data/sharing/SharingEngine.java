package com.xmum.smartgrocery.data.sharing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.dao.AccountDao;
import com.xmum.smartgrocery.data.local.entity.BudgetEntry;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;
import com.xmum.smartgrocery.data.local.entity.InventoryLog;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;
import com.xmum.smartgrocery.data.local.entity.UserEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import kotlin.Pair;
import kotlin.text.StringsKt;

/** Persists outgoing changes and reconciles them with their household's server snapshots. */
public final class SharingEngine {
    private static final List<String> APPLY_ORDER = Arrays.asList("household", "settings", "inventory", "logs", "shopping", "budget");

    private final AppDatabase database;
    private final SharingDao dao;
    private final AccountDao localAccounts;
    private final SharingApi api;
    private final TokenCipher cipher;
    private final ReentrantLock mutex = new ReentrantLock(true);
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    public SharingEngine(@NonNull AppDatabase database, @NonNull SharingApi api, @NonNull TokenCipher cipher) {
        this.database = database;
        this.dao = database.sharingDao();
        this.localAccounts = database.accountDao();
        this.api = api;
        this.cipher = cipher;
    }

    @WorkerThread
    public void prepare() {
        database.runInTransaction(() -> SharingSchema.install(database.getOpenHelper().getWritableDatabase()));
    }

    @WorkerThread
    public void sync(@NonNull CloudAccount account) throws GeneralSecurityException, IOException, InterruptedException {
        mutex.lockInterruptibly();
        try {
            String token = cipher.decrypt(account.getEncryptedToken());
            List<RemoteSnapshot> homes = api.homes(token).getHouseholds();
            Set<String> remoteIds = new HashSet<>();
            for (RemoteSnapshot home : homes) {
                remoteIds.add(home.getHousehold().getId());
            }
            for (CloudHousehold link : dao.householdsNow(account.getServerUrl())) {
                if (!remoteIds.contains(link.getRemoteHouseholdId())
                        && dao.activeNow(link.getLocalHouseholdId(), account.getLocalUserId()) > 0) {
                    revoke(account, link);
                }
            }
            for (RemoteSnapshot snapshot : homes) {
                syncHousehold(account, token, snapshot);
            }
        } finally {
            mutex.unlock();
        }
    }

    @WorkerThread
    @NonNull
    public String importSnapshot(@NonNull CloudAccount account, @NonNull RemoteSnapshot snapshot)
            throws InterruptedException {
        return importSnapshot(account, snapshot, null);
    }

    @WorkerThread
    @NonNull
    public String importSnapshot(@NonNull CloudAccount account, @NonNull RemoteSnapshot snapshot, @Nullable String localId)
            throws InterruptedException {
        mutex.lockInterruptibly();
        try {
            return apply(account, snapshot, Collections.emptyMap(), localId);
        } finally {
            mutex.unlock();
        }
    }

    private void syncHousehold(CloudAccount account, String token, RemoteSnapshot snapshot) throws IOException {
        CloudHousehold known = dao.householdNow(account.getServerUrl(), snapshot.getHousehold().getId());
        boolean retrying = known != null && !dao.outboxNow(known.getLocalHouseholdId()).isEmpty();
        RemoteSnapshot metadata = retrying ? snapshot.copy(snapshot.getHousehold(), snapshot.getMembers(),
                Collections.emptyList(), snapshot.getAcknowledged()) : snapshot;
        String localId = apply(account, metadata, Collections.emptyMap(), null);
        CloudHousehold link = dao.householdNow(localId);
        if (link == null) {
            throw new IllegalStateException("Required value was null.");
        }
        List<PendingChange> pending = capture(account, link);
        List<RemoteMutation> operations = new ArrayList<>(pending.size());
        Map<Pair<String, String>, PendingChange> sent = new HashMap<>();
        for (PendingChange change : pending) {
            operations.add(change.mutation);
            sent.put(new Pair<>(change.mark.getType(), change.mark.getRemoteId()), change);
        }

        RemoteSnapshot result;
        try {
            result = api.sync(token, link.getRemoteHouseholdId(), operations);
        } catch (SharingException error) {
            if (error.getStatus() == 403) {
                revoke(account, link);
            }
            throw error;
        }
        apply(account, result, sent, localId);
    }

    private void ensureCurrent(CloudAccount account) {
        LocalSessionEntity session = localAccounts.sessionNow();
        if (session == null || !account.getLocalUserId().equals(session.getUserId())) {
            throw new IllegalStateException("Account changed. Try again after signing in.");
        }
        CloudAccount active = dao.accountNow(account.getLocalUserId(), account.getServerUrl());
        if (active == null || !account.getRemoteUserId().equals(active.getRemoteUserId())) {
            throw new IllegalStateException("Sharing session changed.");
        }
    }

    private void revoke(CloudAccount account, CloudHousehold link) {
        database.runInTransaction(() -> {
            ensureCurrent(account);
            String id = link.getLocalHouseholdId();
            localAccounts.deleteMembershipNow(id, account.getLocalUserId());
            dao.discardPendingNow(id);
            dao.discardOutboxNow(id);
            LocalSessionEntity session = Objects.requireNonNull(localAccounts.sessionNow());
            if (id.equals(session.getHouseholdId()) || id.equals(session.getActiveHouseholdId())) {
                localAccounts.setSessionNow(session.copy(session.getId(), session.getUserId(), null, null));
            }
        });
    }

    private String resolveUser(CloudAccount account, String id, String name, String email) {
        String server = account.getServerUrl();
        CloudUser existing = dao.userNow(server, id);
        String local = id.equals(account.getRemoteUserId()) ? account.getLocalUserId()
                : existing != null ? existing.getLocalUserId() : "remote:" + namespace(server) + ":" + id;
        UserEntity old = dao.localUserNow(local);
        if (old == null) {
            localAccounts.insertUserNow(new UserEntity(local, "remote-" + id + "@" + namespace(server) + ".invalid",
                    name, "", "", 600000, System.currentTimeMillis()));
        } else if (!StringsKt.isBlank(email) && !old.getDisplayName().equals(name)) {
            dao.updateUserNow(old.copy(old.getUserId(), old.getEmail(), name, old.getPasswordHash(),
                    old.getPasswordSalt(), old.getPasswordIterations(), old.getCreatedAt()));
        }
        String realEmail = !StringsKt.isBlank(email) ? email : existing != null ? existing.getEmail() : "";
        if (existing == null || !existing.getLocalUserId().equals(local) || !existing.getEmail().equals(realEmail)) {
            dao.userNow(new CloudUser(server, id, local, realEmail));
        }
        return local;
    }

    private String apply(CloudAccount account, RemoteSnapshot snapshot, Map<Pair<String, String>, PendingChange> sent,
            String preferredId) {
        return database.runInTransaction(() -> {
            ensureCurrent(account);
            RemoteHousehold remote = snapshot.getHousehold();
            String server = account.getServerUrl();
            CloudHousehold link = dao.householdNow(server, remote.getId());
            String localId = link != null ? link.getLocalHouseholdId()
                    : preferredId != null ? preferredId : "shared:" + namespace(server) + ":" + remote.getId();
            dao.applyingNow(true);
            try {
                reconcileHousehold(localId, remote, server, link == null);
                Map<String, String> profiles = reconcileMembers(account, localId, snapshot.getMembers());
                reconcileRecords(account, localId, snapshot, sent, profiles);
            } finally {
                dao.applyingNow(false);
            }
            return localId;
        });
    }

    private void reconcileHousehold(String localId, RemoteHousehold remote, String server, boolean createLink) {
        HouseholdEntity before = dao.localHouseholdNow(localId);
        HouseholdEntity home = new HouseholdEntity(localId, remote.getName(), remote.getJoinCode(), remote.getDescription());
        if (before == null) {
            localAccounts.insertHouseholdNow(home);
        } else if (!before.equals(home) && dao.marksNow(localId).stream().noneMatch(mark ->
                "household".equals(mark.getType()) && mark.getDirty())) {
            dao.householdNow(home);
        }
        if (createLink) {
            dao.householdNow(new CloudHousehold(localId, server, remote.getId()));
        }
    }

    private Map<String, String> reconcileMembers(CloudAccount account, String localId, List<RemoteMember> members) {
        Map<String, String> profiles = new HashMap<>();
        for (RemoteMember member : members) {
            profiles.put(member.getId(), resolveUser(account, member.getId(), member.getName(), member.getEmail()));
        }
        // Only current server memberships grant access to cached shared data.
        dao.clearMembersNow(localId);
        for (RemoteMember member : members) {
            localAccounts.insertMemberNow(new HouseholdMemberEntity(localId, profiles.get(member.getId()),
                    member.getRole(), member.getStatus(), member.getLastSeenAt()));
        }
        return profiles;
    }

    private void reconcileRecords(CloudAccount account, String localId, RemoteSnapshot snapshot,
            Map<Pair<String, String>, PendingChange> sent, Map<String, String> profiles) {
        Map<Pair<String, String>, SyncEntity> marks = new HashMap<>();
        for (SyncEntity mark : dao.marksNow(localId)) {
            marks.put(new Pair<>(mark.getType(), mark.getRemoteId()), mark);
        }
        List<RemoteRecord> records = new ArrayList<>(snapshot.getRecords());
        records.sort(Comparator.comparingInt(record -> order(record.getCollection())));
        List<String> acknowledged = snapshot.getAcknowledged() != null
                ? snapshot.getAcknowledged() : Collections.emptyList();
        for (RemoteRecord record : records) {
            Pair<String, String> key = new Pair<>(record.getCollection(), record.getId());
            SyncEntity mark = marks.get(key);
            if (mark != null && record.getVersion() < mark.getVersion()) {
                continue;
            }
            PendingChange transmitted = sent.get(key);
            boolean ack = transmitted != null && acknowledged.contains(transmitted.mutation.getMutationId());
            boolean hasNewEdits = mark != null && mark.getDirty()
                    && (!ack || mark.getRevision() != transmitted.mark.getRevision());
            JsonObject localData = mark == null ? null : encode(mark, dao.marksNow(localId));
            if (record.getDeleted()) {
                applyDeletion(localId, record, mark, transmitted, hasNewEdits, localData);
                continue;
            }
            if (mark != null && !mark.getDirty() && mark.getVersion() == record.getVersion()) {
                continue;
            }
            String owner = profiles.get(record.getUserId());
            if (owner == null) {
                owner = resolveUser(account, record.getUserId(), "Former member", "");
            }
            SyncEntity updated = applyRecord(localId, record, mark, transmitted, hasNewEdits, localData, owner);
            dao.markNow(updated);
            marks.put(key, updated);
        }
        if (!sent.isEmpty()) {
            dao.acknowledgeNow(acknowledged);
        }
    }

    private void applyDeletion(String localId, RemoteRecord record, SyncEntity mark, PendingChange transmitted,
            boolean hasNewEdits, JsonObject localData) {
        // An explicit move back to shared can create a new record while a deletion is in flight.
        if (hasNewEdits && transmitted != null && transmitted.mark.getDeleted() && !mark.getDeleted() && localData != null) {
            dao.markNow(mark.copy(mark.getHouseholdId(), mark.getType(), mark.getLocalKey(), UUID.randomUUID().toString(),
                    0, "", true, mark.getDeleted(), mark.getRevision()));
            return;
        }
        if (mark != null) {
            delete(mark);
        }
        dao.markNow(new SyncEntity(localId, record.getCollection(), mark == null ? record.getId() : mark.getLocalKey(),
                record.getId(), record.getVersion(), record.getData().toString(), false, true,
                mark == null ? 1 : mark.getRevision()));
    }

    private SyncEntity applyRecord(String localId, RemoteRecord record, SyncEntity mark, PendingChange transmitted,
            boolean hasNewEdits, JsonObject localData, String owner) {
        JsonObject data = record.getData().deepCopy();
        boolean deleted = hasNewEdits && mark.getDeleted();
        boolean dirty = deleted;
        if (hasNewEdits && !deleted && localData != null) {
            JsonObject base = transmitted != null && transmitted.data != null ? transmitted.data : parse(mark.getBaseline());
            JsonObject patch = diff(localData, base);
            String quantity = quantityField(record.getCollection());
            for (Map.Entry<String, JsonElement> field : patch.entrySet()) {
                if (!field.getKey().equals(quantity)) {
                    data.add(field.getKey(), field.getValue());
                    dirty = true;
                }
            }
            if (quantity != null) {
                int delta = integer(localData, quantity) - integer(base, quantity);
                long quantityAfterEdits = (long) integer(record.getData(), quantity) + delta;
                data.addProperty(quantity, (int) Math.max(0L, Math.min(2_000_000_000L, quantityAfterEdits)));
                dirty |= delta != 0;
            }
        }
        String localKey = deleted ? mark.getLocalKey()
                : store(localId, record.getCollection(), mark == null ? null : mark.getLocalKey(), owner, data);
        return new SyncEntity(localId, record.getCollection(), localKey, record.getId(), record.getVersion(),
                record.getData().toString(), dirty, deleted, mark == null ? 1 : mark.getRevision());
    }

    private List<PendingChange> capture(CloudAccount account, CloudHousehold link) {
        return database.runInTransaction(() -> {
            ensureCurrent(account);
            String id = link.getLocalHouseholdId();
            if (dao.activeNow(id, account.getLocalUserId()) <= 0) {
                throw new IllegalStateException("Household access changed.");
            }
            List<PendingOperation> queued = dao.outboxNow(id);
            List<PendingChange> changes = new ArrayList<>();
            if (!queued.isEmpty()) {
                for (PendingOperation item : queued) {
                    changes.add(new PendingChange(gson.fromJson(item.getMarkJson(), SyncEntity.class),
                            item.getDataJson().isEmpty() ? null : parse(item.getDataJson()),
                            gson.fromJson(item.getMutationJson(), RemoteMutation.class)));
                }
                return changes;
            }

            List<SyncEntity> marks = dao.marksNow(id);
            List<SyncEntity> dirty = new ArrayList<>();
            for (SyncEntity mark : marks) {
                if (mark.getDirty()) {
                    dirty.add(mark);
                }
            }
            dirty.sort(Comparator.comparingInt(mark -> order(mark.getType())));
            for (SyncEntity original : dirty.subList(0, Math.min(dirty.size(), 200))) {
                SyncEntity mark = original;
                JsonObject data = mark.getDeleted() ? null : encode(mark, marks);
                if (!mark.getDeleted() && data == null) {
                    continue;
                }
                SyncEntity canonical = canonicalizeShopping(mark, data, marks);
                if (canonical != mark) {
                    mark = canonical;
                    dao.markNow(mark);
                    marks = dao.marksNow(id);
                }
                changes.add(enqueueChange(id, mark, data));
            }
            return changes;
        });
    }

    private SyncEntity canonicalizeShopping(SyncEntity mark, JsonObject data, List<SyncEntity> marks) {
        JsonElement sourceId = data == null ? null : data.get("sourceId");
        if (!"shopping".equals(mark.getType()) || mark.getVersion() != 0 || sourceId == null || sourceId.isJsonNull()) {
            return mark;
        }
        // Auto-restock suggestions for the same stock item have one shared identity.
        String canonical = "auto:" + sourceId.getAsString();
        if (canonical.equals(mark.getRemoteId()) || marks.stream().anyMatch(other ->
                "shopping".equals(other.getType()) && canonical.equals(other.getRemoteId()))) {
            return mark;
        }
        return mark.copy(mark.getHouseholdId(), mark.getType(), mark.getLocalKey(), canonical, mark.getVersion(),
                mark.getBaseline(), mark.getDirty(), mark.getDeleted(), mark.getRevision());
    }

    private PendingChange enqueueChange(String household, SyncEntity mark, JsonObject data) {
        JsonObject base = parse(mark.getBaseline());
        JsonObject patch = mark.getVersion() == 0 ? data : data == null ? null : diff(data, base);
        String quantity = quantityField(mark.getType());
        Integer delta = quantity != null && mark.getVersion() > 0 && data != null
                ? integer(data, quantity) - integer(base, quantity) : null;
        RemoteMutation mutation = new RemoteMutation(dao.deviceIdNow() + ":" + mark.getRemoteId() + ":" + mark.getRevision(),
                mark.getType(), mark.getRemoteId(), mark.getVersion(), mark.getDeleted(), patch, delta);
        dao.enqueueNow(new PendingOperation(mutation.getMutationId(), household, gson.toJson(mark),
                data == null ? "" : data.toString(), gson.toJson(mutation)));
        return new PendingChange(mark, data, mutation);
    }

    private JsonObject encode(SyncEntity mark, List<SyncEntity> marks) {
        String household = mark.getHouseholdId();
        switch (mark.getType()) {
            case "inventory":
                GroceryItem i = dao.inventoryNow(household, Integer.parseInt(mark.getLocalKey()));
                return i == null ? null : json("name", i.getName(), "quantity", i.getQuantity(), "price", i.getPrice(),
                        "category", i.getCategory(), "subcategory", i.getSubcategory(), "expiryDate", i.getExpiryDate(),
                        "threshold", i.getThreshold(), "purchasedDate", i.getPurchasedDate(),
                        "unit", i.getUnit(), "storageLocation", i.getStorageLocation(),
                        "addedByName", i.getAddedByName(), "lastModifiedByName", i.getLastModifiedByName(),
                        "lastModifiedAction", i.getLastModifiedAction(), "lastModifiedAt", i.getLastModifiedAt());
            case "shopping":
                ShoppingListItem s = dao.shoppingNow(household, Integer.parseInt(mark.getLocalKey()));
                return s == null ? null : json("itemName", s.getItemName(), "quantityNeeded", s.getQuantityNeeded(),
                        "quantityLabel", s.getQuantityLabel(), "estimatedUnitPrice", s.getEstimatedUnitPrice(),
                        "category", s.getCategory(), "subcategory", s.getSubcategory(), "addedBy", s.getAddedBy(),
                        "isChecked", s.isChecked(), "sourceId", remoteInventoryRef(s.getSourceItemId(), marks),
                        "createdAt", s.getCreatedAt(), "updatedAt", s.getUpdatedAt(), "addedByName", s.getAddedByName(),
                        "lastModifiedByName", s.getLastModifiedByName(), "lastModifiedAction", s.getLastModifiedAction(),
                        "lastModifiedAt", s.getLastModifiedAt());
            case "logs":
                InventoryLog log = dao.logNow(household, Integer.parseInt(mark.getLocalKey()));
                return log == null ? null : json("itemId", remoteInventoryRef(log.getItemId(), marks),
                        "itemName", log.getItemName(), "category", log.getCategory(), "changeType", log.getChangeType(),
                        "amountChanged", log.getAmountChanged(), "unitPriceAtChange", log.getUnitPriceAtChange(),
                        "timestamp", log.getTimestamp(), "unit", log.getUnit());
            case "budget":
                BudgetEntry budget = dao.budgetNow(household, mark.getLocalKey());
                return budget == null ? null : json("monthYear", budget.getMonthYear(), "limitAmount", budget.getLimitAmount());
            case "household":
                HouseholdEntity home = dao.localHouseholdNow(household);
                return home == null ? null : json("name", home.getName(), "description", home.getDescription());
            case "settings":
                HouseholdSettingEntity settings = dao.settingsNow(household);
                return settings == null ? null : json("expiryWarningDays", settings.getExpiryWarningDays());
            default:
                return null;
        }
    }

    private String store(String household, String type, String key, String owner, JsonObject data) {
        List<SyncEntity> marks = dao.marksNow(household);
        Integer localId = key == null ? null : StringsKt.toIntOrNull(key);
        switch (type) {
            case "inventory":
                return storeInventory(household, localId, owner, data);
            case "shopping":
                return storeShopping(household, localId, owner, data, marks);
            case "logs":
                InventoryLog oldLog = localId == null ? null : dao.logNow(household, localId);
                if (oldLog != null) {
                    return Integer.toString(oldLog.getId());
                }
                return Long.toString(dao.insertLogNow(new InventoryLog(0,
                        localInventoryRef(household, nullableString(data, "itemId"), marks),
                        string(data, "itemName"), string(data, "category"), string(data, "changeType"),
                        integer(data, "amountChanged"), data.get("unitPriceAtChange").getAsDouble(),
                        data.get("timestamp").getAsLong(), owner, household,
                        nullableString(data, "unit") == null ? "pcs" : string(data, "unit"))));
            case "budget":
                BudgetEntry budget = new BudgetEntry(string(data, "monthYear"), data.get("limitAmount").getAsDouble(),
                        owner, household, "h:" + household);
                if (!budget.equals(dao.budgetNow(household, budget.getMonthYear()))) {
                    dao.budgetNow(budget);
                }
                return budget.getMonthYear();
            case "household":
                HouseholdEntity old = dao.localHouseholdNow(household);
                if (old == null) {
                    throw new IllegalStateException("Required value was null.");
                }
                HouseholdEntity home = old.copy(old.getHouseholdId(), string(data, "name"), old.getJoinCode(),
                        string(data, "description"));
                if (!home.equals(old)) {
                    dao.householdNow(home);
                }
                return household;
            case "settings":
                HouseholdSettingEntity settings = new HouseholdSettingEntity(household, integer(data, "expiryWarningDays"));
                if (!settings.equals(dao.settingsNow(household))) {
                    dao.settingsNow(settings);
                }
                return household;
            default:
                throw new IllegalStateException("Unknown shared record.");
        }
    }

    private String storeInventory(String household, Integer localId, String owner, JsonObject data) {
        GroceryItem previous = localId == null ? null : dao.inventoryNow(household, localId);
        GroceryItem value = new GroceryItem(previous == null ? 0 : previous.getId(), string(data, "name"),
                integer(data, "quantity"), data.get("price").getAsDouble(), string(data, "category"),
                nullableLong(data, "expiryDate"), integer(data, "threshold"), data.get("purchasedDate").getAsLong(),
                nullableString(data, "subcategory"), owner, household, nullableString(data, "addedByName"),
                nullableString(data, "lastModifiedByName"), nullableString(data, "lastModifiedAction"),
                nullableLong(data, "lastModifiedAt"),
                nullableString(data, "unit") == null ? "pcs" : string(data, "unit"),
                nullableString(data, "storageLocation") == null ? "Not set" : string(data, "storageLocation"));
        if (previous == null) {
            return Long.toString(dao.insertInventoryNow(value));
        }
        if (!previous.equals(value)) {
            dao.inventoryNow(value);
        }
        return Integer.toString(previous.getId());
    }

    private String storeShopping(String household, Integer localId, String owner, JsonObject data, List<SyncEntity> marks) {
        ShoppingListItem existing = localId == null ? null : dao.shoppingNow(household, localId);
        Integer sourceId = localInventoryRef(household, nullableString(data, "sourceId"), marks);
        if (existing == null && sourceId != null) {
            // Adopt an existing suggestion before inserting a row with the same inventory source.
            existing = dao.shoppingBySourceNow(household, sourceId);
        }
        ShoppingListItem value = new ShoppingListItem(existing == null ? 0 : existing.getId(), sourceId,
                string(data, "itemName"), string(data, "category"), integer(data, "quantityNeeded"),
                string(data, "quantityLabel"), data.get("estimatedUnitPrice").getAsDouble(), string(data, "addedBy"),
                "Household", data.get("isChecked").getAsBoolean(), data.get("createdAt").getAsLong(),
                data.get("updatedAt").getAsLong(), nullableString(data, "subcategory"), owner, household,
                nullableString(data, "addedByName"), nullableString(data, "lastModifiedByName"),
                nullableString(data, "lastModifiedAction"), nullableLong(data, "lastModifiedAt"));
        if (existing == null) {
            return Long.toString(dao.insertShoppingNow(value));
        }
        if (!existing.equals(value)) {
            dao.shoppingNow(value);
        }
        return Integer.toString(existing.getId());
    }

    private static String remoteInventoryRef(Integer localId, List<SyncEntity> marks) {
        if (localId != null) {
            String key = localId.toString();
            for (SyncEntity mark : marks) {
                if ("inventory".equals(mark.getType()) && key.equals(mark.getLocalKey()) && !mark.getDeleted()) {
                    return mark.getRemoteId();
                }
            }
        }
        return null;
    }

    private Integer localInventoryRef(String household, String remoteId, List<SyncEntity> marks) {
        if (remoteId != null) {
            for (SyncEntity mark : marks) {
                if ("inventory".equals(mark.getType()) && remoteId.equals(mark.getRemoteId()) && !mark.getDeleted()) {
                    int local = Integer.parseInt(mark.getLocalKey());
                    return dao.inventoryNow(household, local) != null ? local : null;
                }
            }
        }
        return null;
    }

    private void delete(SyncEntity mark) {
        String household = mark.getHouseholdId();
        if ("budget".equals(mark.getType())) {
            dao.deleteBudgetNow(household, mark.getLocalKey());
            return;
        }
        Integer id = StringsKt.toIntOrNull(mark.getLocalKey());
        if (id != null) {
            if ("inventory".equals(mark.getType())) {
                dao.deleteInventoryNow(household, id);
            } else if ("shopping".equals(mark.getType())) {
                dao.deleteShoppingNow(household, id);
            }
        }
    }

    private JsonObject json(Object... fields) {
        JsonObject value = new JsonObject();
        for (int i = 0; i < fields.length; i += 2) {
            value.add((String) fields[i], gson.toJsonTree(fields[i + 1]));
        }
        return value;
    }

    private static JsonObject parse(String json) {
        return StringsKt.isBlank(json) ? new JsonObject() : JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject diff(JsonObject value, JsonObject base) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> field : value.entrySet()) {
            if (!Objects.equals(base.get(field.getKey()), field.getValue())) {
                result.add(field.getKey(), field.getValue());
            }
        }
        return result;
    }

    private static int order(String type) {
        int index = APPLY_ORDER.indexOf(type);
        return index >= 0 ? index : 99;
    }

    private static String quantityField(String type) {
        if ("inventory".equals(type)) {
            return "quantity";
        }
        return "shopping".equals(type) ? "quantityNeeded" : null;
    }

    private static int integer(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field == null || field.isJsonNull() ? 0 : field.getAsInt();
    }

    private static String string(JsonObject value, String key) {
        return value.get(key).getAsString();
    }

    private static String nullableString(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field == null || field.isJsonNull() ? null : field.getAsString();
    }

    private static Long nullableLong(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field == null || field.isJsonNull() ? null : field.getAsLong();
    }

    @NonNull
    public static String namespace(@NonNull String server) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8));
            StringBuilder namespace = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                int value = bytes[i] & 0xff;
                namespace.append(Character.forDigit(value >>> 4, 16));
                namespace.append(Character.forDigit(value & 0x0f, 16));
            }
            return namespace.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static final class PendingChange {
        private final SyncEntity mark;
        private final JsonObject data;
        private final RemoteMutation mutation;

        private PendingChange(SyncEntity mark, JsonObject data, RemoteMutation mutation) {
            this.mark = mark;
            this.data = data;
            this.mutation = mutation;
        }
    }
}

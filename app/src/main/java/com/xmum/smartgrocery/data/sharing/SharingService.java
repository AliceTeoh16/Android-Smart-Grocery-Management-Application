package com.xmum.smartgrocery.data.sharing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.dao.AccountDao;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberStatus;
import com.xmum.smartgrocery.data.local.entity.HouseholdRole;
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.local.entity.UserEntity;
import com.xmum.smartgrocery.data.session.PasswordHasher;
import com.xmum.smartgrocery.data.session.SessionManager;
import com.xmum.smartgrocery.utils.AndroidCoroutines;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

public final class SharingService {
    private final AppDatabase database;
    private final AccountDao accounts;
    private final SharingDao dao;
    private final Supplier<String> serverUrl;
    private final TokenCipher cipher;
    private final Map<String, SharingEngine> engines = new HashMap<>();
    private final ReentrantLock authMutex = new ReentrantLock(true);
    private final ReentrantLock operations = new ReentrantLock(true);
    private final MutableStateFlow<SharingStatus> currentStatus = StateFlowKt.MutableStateFlow(new SharingStatus());
    private final StateFlow<SharingStatus> status = FlowKt.asStateFlow(currentStatus);

    public SharingService(@NonNull AppDatabase database, @NonNull Supplier<String> serverUrl) {
        this(database, serverUrl, new KeystoreTokenCipher());
    }

    public SharingService(@NonNull AppDatabase database, @NonNull Supplier<String> serverUrl,
            @NonNull TokenCipher cipher) {
        this.database = database;
        this.serverUrl = serverUrl;
        this.cipher = cipher;
        accounts = database.accountDao();
        dao = database.sharingDao();
    }

    @NonNull
    public AppDatabase getDatabase() { return database; }

    @NonNull
    public StateFlow<SharingStatus> getStatus() { return status; }

    @Nullable
    public Object register(@NonNull String name, @NonNull String email, @NonNull String password,
            @NonNull Continuation<? super String> completion) {
        return onIo(authMutex, () -> registerNow(name, email, password), completion);
    }

    @Nullable
    public Object login(@NonNull String email, @NonNull String password,
            @NonNull Continuation<? super String> completion) {
        return onIo(authMutex, () -> loginNow(email, password), completion);
    }

    @NonNull
    public Object synchronize(@NonNull Continuation<? super Unit> completion) {
        return onIo(operations, () -> {
            synchronizeNow();
            return Unit.INSTANCE;
        }, completion);
    }

    @NonNull
    public Object createHousehold(@NonNull String name, @NonNull String description,
            @NonNull Continuation<? super HouseholdEntity> completion) {
        return onIo(operations, () -> {
            Connection connection = connection();
            RemoteSnapshot snapshot = connection.api.create(token(connection.account), name, description);
            return importAndSelectHousehold(connection, snapshot);
        }, completion);
    }

    @NonNull
    public Object publishHousehold(@NonNull String localId, @NonNull Continuation<? super Unit> completion) {
        return onIo(operations, () -> {
            publishNow(localId);
            return Unit.INSTANCE;
        }, completion);
    }

    @NonNull
    public Object joinHousehold(@NonNull String code, @NonNull Continuation<? super HouseholdEntity> completion) {
        return onIo(operations, () -> {
            Connection connection = connection();
            RemoteSnapshot snapshot = connection.api.join(token(connection.account), trim(code));
            return importAndSelectHousehold(connection, snapshot);
        }, completion);
    }

    @NonNull
    public Object invite(@NonNull String householdId, @NonNull String email,
            @NonNull Continuation<? super String> completion) {
        return onIo(operations, () -> {
            Connection connection = connection();
            CloudHousehold link = dao.householdNow(householdId);
            if (link == null) {
                throw new IllegalArgumentException("Enable cross-device sharing for this household first.");
            }
            requireSameServer(connection, link);
            RemoteSnapshot snapshot = connection.api.invite(token(connection.account), link.getRemoteHouseholdId(), email);
            return engine(connection.api).importSnapshot(connection.account, snapshot);
        }, completion);
    }

    @NonNull
    public Object leave(@NonNull String householdId, @NonNull Continuation<? super Unit> completion) {
        return onIo(operations, () -> {
            CloudHousehold link = dao.householdNow(householdId);
            if (link != null) {
                Connection connection = connection();
                requireSameServer(connection, link);
                synchronizeNow();
                connection.api.leave(token(connection.account), link.getRemoteHouseholdId());
                dao.discardPendingNow(householdId);
            }
            return Unit.INSTANCE;
        }, completion);
    }

    @NonNull
    public Object logout(@NonNull Continuation<? super Unit> completion) {
        return onIo(operations, () -> {
            logoutNow();
            return Unit.INSTANCE;
        }, completion);
    }

    @Nullable
    private String registerNow(String name, String email, String password) {
        try {
            if (accounts.findByEmailNow(trim(email).toLowerCase(Locale.ROOT)) != null) {
                return "An account already exists on this device. Log in to connect it.";
            }
            SharingApi api = api();
            bind(api, api.register(name, email, password), password);
            return null;
        } catch (Exception error) {
            return message(error, "Unable to create the sharing account.");
        }
    }

    @Nullable
    private String loginNow(String email, String password) throws GeneralSecurityException {
        String normalized = trim(email).toLowerCase(Locale.ROOT);
        UserEntity local = accounts.findByEmailNow(normalized);
        try {
            SharingApi api = api();
            RemoteAuth auth;
            try {
                auth = api.login(normalized, password);
            } catch (SharingException error) {
                if (!"ACCOUNT_NOT_FOUND".equals(error.getCode()) || local == null) {
                    throw error;
                }
                if (!verify(local, password)) {
                    return "Email or password is incorrect.";
                }
                auth = api.register(local.getDisplayName(), normalized, password);
            }
            bind(api, auth, password);
            return null;
        } catch (Exception error) {
            if (error instanceof IOException && !(error instanceof SharingException) && local != null
                    && verify(local, password)) {
                String id = local.getUserId();
                accounts.setSessionNow(new LocalSessionEntity(1, id, null, firstHouseholdId(id)));
                setStatus(false, false,
                        "Sharing server is offline. Personal data and cached household data are available on this device.");
                return null;
            }
            return message(error, "Cannot reach the sharing server. Check its address and connection.");
        }
    }

    private void bind(SharingApi api, RemoteAuth auth, String password) throws Exception {
        engine(api).prepare();
        String server = api.getBaseUrl();
        RemoteUser remote = auth.getUser();
        UserEntity local = accounts.findByEmailNow(remote.getEmail());
        CloudUser mapping = dao.userNow(server, remote.getId());
        if (local != null && (mapping == null || !local.getUserId().equals(mapping.getLocalUserId()))) {
            CloudUser prior = dao.userForLocalNow(local.getUserId(), server);
            if (prior != null && !prior.getRemoteUserId().equals(remote.getId())) {
                throw new IllegalArgumentException("A different sharing account is linked to this local profile.");
            }
            if (!verify(local, password)) {
                throw new IllegalArgumentException("This email belongs to a different local profile. "
                        + "Its private data cannot be claimed by this sharing account.");
            }
        }
        String id = local != null ? local.getUserId() : mapping != null ? mapping.getLocalUserId()
                : "account:" + SharingEngine.namespace(server) + ":" + remote.getId();
        String salt = PasswordHasher.salt();
        String passwordHash = PasswordHasher.hash(password, salt);
        String encrypted = cipher.encrypt(auth.getToken());
        database.runInTransaction(() -> {
            // A successful login with the same remote identity proves an address relocation.
            // Keep existing household IDs and queued offline edits when moving behind the gateway.
            SharingEndpointMigration.relocate(database, id, remote.getId(), server);
            UserEntity old = dao.localUserNow(id);
            UserEntity user = new UserEntity(id, remote.getEmail(), remote.getName(), passwordHash, salt,
                    PasswordHasher.ITERATIONS, old == null ? System.currentTimeMillis() : old.getCreatedAt());
            if (old == null) {
                accounts.insertUserNow(user);
            } else {
                dao.updateUserNow(user);
            }
            dao.userNow(new CloudUser(server, remote.getId(), id, remote.getEmail()));
            dao.accountNow(new CloudAccount(id, server, remote.getId(), encrypted, auth.getExpiresAt()));
            LocalSessionEntity session = accounts.sessionNow();
            String active = session != null && id.equals(session.getUserId()) ? session.getActiveHouseholdId() : null;
            accounts.setSessionNow(new LocalSessionEntity(1, id, null, active != null ? active : firstHouseholdId(id)));
        });
        setStatus(true, false, "Connected to household sharing.");
        // The initial refresh can fail after authentication has succeeded.
        try {
            locked(operations, () -> {
                synchronizeNow();
                return Unit.INSTANCE;
            });
        } catch (Exception ignored) {
        }
    }

    private void synchronizeNow() throws Exception {
        LocalSessionEntity session = accounts.sessionNow();
        SharingApi api = api();
        String user = session == null ? null : session.getUserId();
        CloudAccount account = user == null ? null : dao.accountNow(user, api.getBaseUrl());
        if (account == null) {
            currentStatus.setValue(new SharingStatus());
            return;
        }
        setStatus(true, true, "Syncing household changes…");
        try {
            SharingEngine engine = engine(api);
            engine.prepare();
            engine.sync(account);
            setStatus(true, false, "Household data is up to date.");
        } catch (Exception error) {
            if (error instanceof SharingException && ((SharingException) error).getStatus() == 401) {
                dao.forgetAccountNow(account.getLocalUserId(), account.getServerUrl());
                setStatus(false, false, "Sharing session expired. Connect your account again.");
            } else {
                String message = error instanceof SharingException ? message(error, "")
                        : "Sharing is offline. Shared changes are saved on this device and will sync when the server is reachable.";
                setStatus(true, false, message);
            }
            throw error;
        }
    }

    private HouseholdEntity importAndSelectHousehold(Connection connection, RemoteSnapshot snapshot) throws InterruptedException {
        String id = engine(connection.api).importSnapshot(connection.account, snapshot);
        database.runInTransaction(() -> new SessionManager(database).selectHouseholdNow(id));
        return requireHousehold(id);
    }

    private void publishNow(String localId) throws Exception {
        Connection connection = connection();
        if (dao.householdNow(localId) != null) {
            throw new IllegalArgumentException("This household is already connected.");
        }
        HouseholdMemberEntity member = database.householdDao().membershipNow(localId, connection.account.getLocalUserId());
        if (member == null || !HouseholdRole.OWNER.equals(member.getRole())
                || !HouseholdMemberStatus.ACTIVE.equals(member.getStatus())) {
            throw new IllegalArgumentException("Only the owner can enable cross-device sharing.");
        }
        HouseholdEntity home = requireHousehold(localId);
        HouseholdSettingEntity settings = dao.settingsNow(localId);
        int days = settings == null ? 3 : settings.getExpiryWarningDays();
        RemoteSnapshot remote = connection.api.create(token(connection.account), home.getName(), home.getDescription());
        engine(connection.api).importSnapshot(connection.account, remote, localId);
        database.runInTransaction(() -> {
            SharingSchema.bootstrap(database.getOpenHelper().getWritableDatabase(), localId);
            dao.settingsNow(new HouseholdSettingEntity(localId, days));
        });
        synchronizeNow();
    }

    private void logoutNow() {
        LocalSessionEntity session = accounts.sessionNow();
        if (session == null || session.getUserId() == null) {
            return;
        }
        String user = session.getUserId();
        SharingApi api = api();
        CloudAccount account = dao.accountNow(user, api.getBaseUrl());
        if (account != null) {
            try {
                api.logout(token(account));
            } catch (Exception ignored) {
                // The local session can still be removed while offline.
            }
            dao.forgetAccountNow(user, api.getBaseUrl());
        }
        currentStatus.setValue(new SharingStatus());
    }

    private Connection connection() {
        SharingApi api = api();
        LocalSessionEntity session = accounts.sessionNow();
        if (session == null || session.getUserId() == null) {
            throw new IllegalStateException("Sign in first.");
        }
        CloudAccount account = dao.accountNow(session.getUserId(), api.getBaseUrl());
        if (account == null) {
            throw new IllegalStateException("Connect your account in Share & Connect first.");
        }
        if (account.getExpiresAt() <= System.currentTimeMillis()) {
            throw new IllegalStateException("Sign in to the sharing server again.");
        }
        return new Connection(api, account);
    }

    private void requireSameServer(Connection connection, CloudHousehold link) {
        if (!connection.api.getBaseUrl().equals(link.getServerUrl())) {
            throw new IllegalArgumentException("Choose this household's sharing server first.");
        }
    }

    private HouseholdEntity requireHousehold(String id) {
        HouseholdEntity home = dao.localHouseholdNow(id);
        if (home == null) {
            throw new IllegalStateException("Required value was null.");
        }
        return home;
    }

    private SharingApi api() {
        return new SharingApi(SharingApi.normalizeUrl(serverUrl.get()));
    }

    private SharingEngine engine(SharingApi api) {
        synchronized (engines) {
            return engines.computeIfAbsent(api.getBaseUrl(), url -> new SharingEngine(database, api, cipher));
        }
    }

    private String token(CloudAccount account) throws GeneralSecurityException, IOException {
        return cipher.decrypt(account.getEncryptedToken());
    }

    private String firstHouseholdId(String user) {
        List<HouseholdEntity> homes = accounts.householdsForNow(user);
        return homes.isEmpty() ? null : homes.get(0).getHouseholdId();
    }

    private void setStatus(boolean connected, boolean syncing, String message) {
        currentStatus.setValue(new SharingStatus(connected, syncing, message));
    }

    private static boolean verify(UserEntity user, String password) throws GeneralSecurityException {
        return PasswordHasher.verify(password, user.getPasswordSalt(), user.getPasswordIterations(), user.getPasswordHash());
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }

    private static String message(Exception error, String fallback) {
        if (error instanceof IOException && !(error instanceof SharingException)) {
            return "Cannot connect right now. Check your connection and try again.";
        }
        return error.getMessage() != null ? error.getMessage() : fallback;
    }

    private static <T> Object onIo(ReentrantLock lock, Callable<T> task, Continuation<? super T> completion) {
        return AndroidCoroutines.onIoInterruptible(() -> locked(lock, task), completion);
    }

    private static <T> T locked(ReentrantLock lock, Callable<T> task) throws Exception {
        lock.lockInterruptibly();
        try {
            return task.call();
        } finally {
            lock.unlock();
        }
    }

    private static final class Connection {
        private final SharingApi api;
        private final CloudAccount account;

        private Connection(SharingApi api, CloudAccount account) {
            this.api = api;
            this.account = account;
        }
    }
}

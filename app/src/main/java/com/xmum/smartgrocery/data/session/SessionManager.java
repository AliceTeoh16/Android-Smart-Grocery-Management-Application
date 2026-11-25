package com.xmum.smartgrocery.data.session;

import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.room.CoroutinesRoom;
import androidx.room.RoomDatabaseKt;

import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.dao.AccountDao;
import com.xmum.smartgrocery.data.local.dao.DataContextInfo;
import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.dao.HouseholdDao;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberStatus;
import com.xmum.smartgrocery.data.local.entity.HouseholdRole;
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.local.entity.UserEntity;
import com.xmum.smartgrocery.data.sharing.SharingRuntime;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.SuspendCall;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

/** Manages local authentication and personal or household data context. */
public final class SessionManager {
    private final AppDatabase database;
    private final AccountDao accountDao;
    private final HouseholdDao householdDao;
    private final Flow<LocalSessionEntity> sessions;
    private final Flow<DataContextInfo> context;

    public SessionManager(@NonNull AppDatabase database) {
        this.database = database;
        accountDao = database.accountDao();
        householdDao = database.householdDao();
        // Stable flow identities keep Compose collectors subscribed across redraws.
        sessions = FlowKt.distinctUntilChanged(accountDao.observeSession());
        context = FlowKt.distinctUntilChanged(accountDao.observeContext());
    }

    @NonNull
    public Flow<LocalSessionEntity> getSessions() {
        return sessions;
    }

    @NonNull
    public Flow<DataContextInfo> getContext() {
        return context;
    }

    @Nullable
    public Object currentUserId(@NonNull Continuation<? super String> completion) {
        return read(this::currentUserIdNow, completion);
    }

    @Nullable
    public Object currentSession(@NonNull Continuation<? super LocalSessionEntity> completion) {
        return accountDao.session(completion);
    }

    @Nullable
    public Object currentUser(@NonNull Continuation<? super UserEntity> completion) {
        return accountDao.currentUser(completion);
    }

    /** A reviewed destination and its writes commit under the account that opened the form. */
    @Nullable
    public <T> Object runInInventoryDestination(@NonNull DataScope target, @NonNull SuspendCall<T> action,
            @NonNull Continuation<? super T> completion) {
        return RoomDatabaseKt.withTransaction(database, next -> {
            if (!target.getUserId().equals(currentUserIdNow())) {
                throw new IllegalStateException("Account changed. Reopen the inventory form before saving.");
            }
            selectHouseholdNow(target.getHouseholdId());
            return action.invoke(next);
        }, completion);
    }

    @Nullable
    public Object register(@NonNull String name, @NonNull String email, @NonNull String password,
            @NonNull Continuation<? super String> completion) {
        return AndroidCoroutines.onIo(() -> registerNow(name, email, password), completion);
    }

    @Nullable
    public Object signIn(@NonNull String email, @NonNull String password,
            @NonNull Continuation<? super String> completion) {
        return AndroidCoroutines.onIo(() -> signInNow(email, password), completion);
    }

    @NonNull
    public Object signOut(@NonNull Continuation<? super Unit> completion) {
        return write(() -> {
            String id = currentUserIdNow();
            if (id != null) {
                householdDao.touchMemberNow(id, System.currentTimeMillis());
            }
            accountDao.setSessionNow(new LocalSessionEntity());
        }, completion);
    }

    /** Chooses the target and applies the shopping mutation inside a single transaction. */
    @Nullable
    public <T> Object runInShoppingScope(@NonNull String assignedTo, @NonNull SuspendCall<T> action,
            @NonNull Continuation<? super T> completion) {
        return AndroidCoroutines.<ShoppingScopeStart, T>then(
                next -> read(() -> {
                    String expectedUser = currentUserIdNow();
                    SharingRuntime runtime = "Household".equals(assignedTo) ? sharing() : null;
                    return new ShoppingScopeStart(expectedUser, runtime != null && householdsNow().isEmpty());
                }, next),
                (start, next) -> {
                    if (!start.createSharedHome) {
                        return inShoppingTransaction(assignedTo, start.expectedUser, action, next);
                    }
                    return AndroidCoroutines.<HouseholdEntity, T>then(
                            created -> createHousehold("Home Pantry", "", created),
                            (home, resumed) -> inShoppingTransaction(assignedTo, start.expectedUser, action, resumed), next);
                }, completion);
    }

    @NonNull
    public Object households(@NonNull Continuation<? super List<HouseholdEntity>> completion) {
        return read(this::householdsNow, completion);
    }

    @NonNull
    public Object selectHousehold(@Nullable String householdId, @NonNull Continuation<? super Unit> completion) {
        return write(() -> selectHouseholdNow(householdId), completion);
    }

    @NonNull
    public Object selectScope(boolean household, @NonNull Continuation<? super Unit> completion) {
        return write(() -> {
            if (!household) {
                selectHouseholdNow(null);
                return;
            }
            LocalSessionEntity session = requireSession();
            HouseholdEntity active = pickHousehold(householdsNow(), session.getActiveHouseholdId());
            if (active == null) {
                throw new IllegalArgumentException("Create or join a household first.");
            }
            selectHouseholdNow(active.getHouseholdId());
        }, completion);
    }

    @NonNull
    public Object createHousehold(@NonNull String name, @NonNull Continuation<? super HouseholdEntity> completion) {
        return createHousehold(name, "", completion);
    }

    @NonNull
    public Object createHousehold(@NonNull String name, @NonNull String description,
            @NonNull Continuation<? super HouseholdEntity> completion) {
        SharingRuntime runtime = sharing();
        if (runtime != null) {
            return runtime.getService().createHousehold(name, description, completion);
        }
        return writeValue(() -> createLocalHousehold(name, description), completion);
    }

    @NonNull
    public Object joinHousehold(@NonNull String code, @NonNull Continuation<? super HouseholdEntity> completion) {
        SharingRuntime runtime = sharing();
        if (runtime != null) {
            return runtime.getService().joinHousehold(code, completion);
        }
        return writeValue(() -> joinLocalHousehold(code), completion);
    }

    @NonNull
    public Object leaveHousehold(@NonNull String householdId, @NonNull Continuation<? super Unit> completion) {
        SharingRuntime runtime = sharing();
        if (runtime == null) {
            return write(() -> leaveLocalHousehold(householdId), completion);
        }
        return AndroidCoroutines.<Unit, Unit>then(next -> runtime.getService().leave(householdId, next),
                (unused, next) -> write(() -> leaveLocalHousehold(householdId), next), completion);
    }

    private <T> Object inShoppingTransaction(String assignedTo, String expectedUser, SuspendCall<T> action,
            Continuation<? super T> completion) {
        return RoomDatabaseKt.withTransaction(database, next -> {
            if (!"Me".equals(assignedTo) && !"Household".equals(assignedTo)) {
                throw new IllegalArgumentException("Choose Me or Household.");
            }
            LocalSessionEntity session = requireSession();
            if (!Objects.equals(session.getUserId(), expectedUser)) {
                throw new IllegalStateException("Account changed. Review the shopping item again.");
            }
            if (session.getUserId() == null) {
                throw new IllegalStateException("Sign in first.");
            }
            if ("Me".equals(assignedTo)) {
                selectHouseholdNow(null);
            } else if (session.getHouseholdId() == null) {
                HouseholdEntity home = pickHousehold(householdsNow(), session.getActiveHouseholdId());
                if (home == null) {
                    if (sharing() != null) {
                        throw new IllegalStateException("Create or join a household first.");
                    }
                    home = createLocalHousehold("Home Pantry", "");
                }
                selectHouseholdNow(home.getHouseholdId());
            }
            return action.invoke(next);
        }, completion);
    }

    @Nullable
    private String registerNow(String name, String email, String password) throws GeneralSecurityException {
        if (StringsKt.isBlank(name)) {
            return "Enter your full name.";
        }
        if (password.length() < 8 || password.chars().noneMatch(Character::isDigit)) {
            return "Use at least 8 characters, with a number.";
        }
        String normalized = trim(email).toLowerCase(Locale.ROOT);
        if (!Patterns.EMAIL_ADDRESS.matcher(normalized).matches()) {
            return "Enter a valid email address.";
        }
        if (accountDao.findByEmailNow(normalized) != null) {
            return "An account already exists for this email.";
        }
        String salt = PasswordHasher.salt();
        String hash = PasswordHasher.hash(password, salt);
        return database.runInTransaction(() -> {
            if (accountDao.findByEmailNow(normalized) != null) {
                return "An account already exists for this email.";
            }
            UserEntity user = new UserEntity(UUID.randomUUID().toString(), normalized, trim(name), hash, salt,
                    PasswordHasher.ITERATIONS, System.currentTimeMillis());
            accountDao.insertUserNow(user);
            accountDao.setSessionNow(new LocalSessionEntity(1, user.getUserId(), null, null));
            return null;
        });
    }

    @Nullable
    private String signInNow(String email, String password) throws GeneralSecurityException {
        UserEntity user = accountDao.findByEmailNow(trim(email).toLowerCase(Locale.ROOT));
        if (user == null) {
            return "No account found with this email. Please sign up to create an account first.";
        }
        if (!PasswordHasher.verify(password, user.getPasswordSalt(), user.getPasswordIterations(), user.getPasswordHash())) {
            return "Email or password is incorrect.";
        }
        // An account switch starts in the private context.
        database.runInTransaction(() -> {
            String id = user.getUserId();
            householdDao.touchMemberNow(id, System.currentTimeMillis());
            accountDao.setSessionNow(new LocalSessionEntity(1, id, null, firstHouseholdId(accountDao.householdsForNow(id))));
        });
        return null;
    }

    @WorkerThread
    public void selectHouseholdNow(@Nullable String householdId) {
        String userId = requireUserId("Sign in first.");
        List<HouseholdEntity> homes = accountDao.householdsForNow(userId);
        if (householdId != null && homes.stream().noneMatch(home -> householdId.equals(home.getHouseholdId()))) {
            throw new IllegalArgumentException("You are not a member of this household.");
        }
        LocalSessionEntity current = accountDao.sessionNow();
        String selected = householdId != null ? householdId : current == null ? null : current.getActiveHouseholdId();
        String active = selected != null ? selected : firstHouseholdId(homes);
        accountDao.setSessionNow(new LocalSessionEntity(1, userId, householdId, active));
        householdDao.touchMemberNow(userId, System.currentTimeMillis());
    }

    private HouseholdEntity createLocalHousehold(String name, String description) {
        String userId = requireUserId("Sign in first.");
        if (StringsKt.isBlank(name)) {
            throw new IllegalArgumentException("Enter a household name.");
        }
        SecureRandom random = new SecureRandom();
        String code;
        do {
            code = Integer.toString(100000 + random.nextInt(900000));
        } while (accountDao.householdByCodeNow(code) != null);
        HouseholdEntity home = new HouseholdEntity(UUID.randomUUID().toString(), trim(name), code, trim(description));
        accountDao.insertHouseholdNow(home);
        accountDao.insertMemberNow(new HouseholdMemberEntity(home.getHouseholdId(), userId, HouseholdRole.OWNER,
                HouseholdMemberStatus.ACTIVE, System.currentTimeMillis()));
        selectJoinedHousehold(home, userId);
        return home;
    }

    private HouseholdEntity joinLocalHousehold(String code) {
        String userId = requireUserId("Sign in before joining a household.");
        HouseholdEntity home = accountDao.householdByCodeNow(trim(code));
        if (home == null) {
            throw new IllegalArgumentException("Household code not found on this device.");
        }
        String id = home.getHouseholdId();
        accountDao.insertMemberNow(new HouseholdMemberEntity(id, userId, HouseholdRole.MEMBER,
                HouseholdMemberStatus.ACTIVE, null));
        householdDao.acceptMembershipNow(id, userId, System.currentTimeMillis());
        selectJoinedHousehold(home, userId);
        return home;
    }

    private void selectJoinedHousehold(HouseholdEntity home, String userId) {
        String id = home.getHouseholdId();
        householdDao.initializeSettingsNow(new HouseholdSettingEntity(id, 3));
        accountDao.setSessionNow(new LocalSessionEntity(1, userId, id, id));
    }

    private void leaveLocalHousehold(String householdId) {
        LocalSessionEntity session = requireSession();
        String userId = session.getUserId();
        if (userId == null) {
            throw new IllegalStateException("Sign in first.");
        }
        accountDao.deleteMembershipNow(householdId, userId);
        String selected = session.getHouseholdId();
        String active = session.getActiveHouseholdId();
        if (householdId.equals(selected)) {
            selected = null;
        }
        if (householdId.equals(active)) {
            active = firstHouseholdId(accountDao.householdsForNow(userId));
        }
        accountDao.setSessionNow(new LocalSessionEntity(session.getId(), userId, selected, active));
    }

    private LocalSessionEntity requireSession() {
        LocalSessionEntity session = accountDao.sessionNow();
        if (session == null) {
            throw new IllegalStateException("Sign in first.");
        }
        return session;
    }

    private String requireUserId(String message) {
        String id = currentUserIdNow();
        if (id == null) {
            throw new IllegalStateException(message);
        }
        return id;
    }

    private String currentUserIdNow() {
        LocalSessionEntity session = accountDao.sessionNow();
        return session != null ? session.getUserId() : null;
    }

    private List<HouseholdEntity> householdsNow() {
        String id = currentUserIdNow();
        return id != null ? accountDao.householdsForNow(id) : Collections.emptyList();
    }

    private static HouseholdEntity pickHousehold(List<HouseholdEntity> homes, String preferred) {
        for (HouseholdEntity home : homes) {
            if (home.getHouseholdId().equals(preferred)) {
                return home;
            }
        }
        return homes.isEmpty() ? null : homes.get(0);
    }

    private static String firstHouseholdId(List<HouseholdEntity> homes) {
        return homes.isEmpty() ? null : homes.get(0).getHouseholdId();
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }

    private SharingRuntime sharing() {
        return SharingRuntime.forDatabase(database);
    }

    private <T> Object read(Callable<T> query, Continuation<? super T> completion) {
        return CoroutinesRoom.execute(database, false, query, completion);
    }

    private <T> Object writeValue(Callable<T> task, Continuation<? super T> completion) {
        return CoroutinesRoom.execute(database, true, () -> database.runInTransaction(task), completion);
    }

    private Object write(Runnable task, Continuation<? super Unit> completion) {
        return writeValue(() -> {
            task.run();
            return Unit.INSTANCE;
        }, completion);
    }

    private static final class ShoppingScopeStart {
        private final String expectedUser;
        private final boolean createSharedHome;

        private ShoppingScopeStart(String expectedUser, boolean createSharedHome) {
            this.expectedUser = expectedUser;
            this.createSharedHome = createSharedHome;
        }
    }
}

package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.room.CoroutinesRoom;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RoomDatabase;

import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberEntity;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.local.entity.UserEntity;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Dao
public abstract class AccountDao {
    private final RoomDatabase database;

    protected AccountDao(@NonNull RoomDatabase database) {
        this.database = database;
    }

    @WorkerThread
    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    @Nullable
    public abstract UserEntity findByEmailNow(@NonNull String email);

    @Nullable
    public final Object findByEmail(@NonNull String email,
            @NonNull Continuation<? super UserEntity> continuation) {
        return CoroutinesRoom.execute(database, false, () -> findByEmailNow(email), continuation);
    }

    @WorkerThread
    @Query("SELECT u.* FROM users u JOIN local_session s ON s.userId = u.userId WHERE s.id = 1")
    @Nullable
    public abstract UserEntity currentUserNow();

    @Nullable
    public final Object currentUser(@NonNull Continuation<? super UserEntity> continuation) {
        return CoroutinesRoom.execute(database, false, this::currentUserNow, continuation);
    }

    @WorkerThread
    @Insert
    public abstract void insertUserNow(@NonNull UserEntity user);

    @NonNull
    public final Object insertUser(@NonNull UserEntity user,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> insertUserNow(user), continuation);
    }

    @WorkerThread
    @Insert
    public abstract void insertHouseholdNow(@NonNull HouseholdEntity household);

    @NonNull
    public final Object insertHousehold(@NonNull HouseholdEntity household,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> insertHouseholdNow(household), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract void insertMemberNow(@NonNull HouseholdMemberEntity member);

    @NonNull
    public final Object insertMember(@NonNull HouseholdMemberEntity member,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> insertMemberNow(member), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void setSessionNow(@NonNull LocalSessionEntity session);

    @NonNull
    public final Object setSession(@NonNull LocalSessionEntity session,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> setSessionNow(session), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM local_session WHERE id = 1")
    @Nullable
    public abstract LocalSessionEntity sessionNow();

    @Nullable
    public final Object session(@NonNull Continuation<? super LocalSessionEntity> continuation) {
        return CoroutinesRoom.execute(database, false, this::sessionNow, continuation);
    }

    @Query("SELECT * FROM local_session WHERE id = 1")
    @NonNull
    public abstract Flow<LocalSessionEntity> observeSession();

    @Query("SELECT COALESCE(h.name, 'Personal (private)') AS name, (SELECT COUNT(*) FROM household_members m WHERE m.householdId = s.householdId AND m.status = 'Active') AS memberCount FROM local_session s LEFT JOIN households h ON h.householdId = s.householdId WHERE s.id = 1 AND s.userId IS NOT NULL")
    @NonNull
    public abstract Flow<DataContextInfo> observeContext();

    @WorkerThread
    @Query("SELECT h.* FROM households h JOIN household_members m ON m.householdId = h.householdId WHERE m.userId = :userId AND m.status = 'Active' ORDER BY h.name, h.householdId")
    @NonNull
    public abstract List<HouseholdEntity> householdsForNow(@NonNull String userId);

    @NonNull
    public final Object householdsFor(@NonNull String userId,
            @NonNull Continuation<? super List<HouseholdEntity>> continuation) {
        return CoroutinesRoom.execute(database, false, () -> householdsForNow(userId), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM households WHERE joinCode = :code LIMIT 1")
    @Nullable
    public abstract HouseholdEntity householdByCodeNow(@NonNull String code);

    @Nullable
    public final Object householdByCode(@NonNull String code,
            @NonNull Continuation<? super HouseholdEntity> continuation) {
        return CoroutinesRoom.execute(database, false, () -> householdByCodeNow(code), continuation);
    }

    @WorkerThread
    @Query("SELECT u.* FROM users u JOIN household_members m ON m.userId = u.userId WHERE m.householdId = :householdId")
    @NonNull
    public abstract List<UserEntity> membersNow(@NonNull String householdId);

    @NonNull
    public final Object members(@NonNull String householdId,
            @NonNull Continuation<? super List<UserEntity>> continuation) {
        return CoroutinesRoom.execute(database, false, () -> membersNow(householdId), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM household_members WHERE householdId = :householdId AND userId = :userId")
    public abstract void deleteMembershipNow(@NonNull String householdId, @NonNull String userId);

    @NonNull
    public final Object deleteMembership(@NonNull String householdId, @NonNull String userId,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteMembershipNow(householdId, userId), continuation);
    }

    @Nullable
    private Object executeWrite(Runnable statement, Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(database, true, () -> {
            statement.run();
            return Unit.INSTANCE;
        }, continuation);
    }
}

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
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Dao
public abstract class HouseholdDao {
    private final RoomDatabase database;

    protected HouseholdDao(@NonNull RoomDatabase database) {
        this.database = database;
    }

    @Query("SELECT * FROM households WHERE householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS)
    @NonNull
    public abstract Flow<HouseholdEntity> observeHousehold();

    @Query("SELECT m.householdId, u.userId, u.displayName, COALESCE(c.email,u.email) AS email, m.role, m.status, m.lastSeenAt, u.userId = " + ScopeSql.USER + " AS isCurrentUser FROM household_members m JOIN users u ON u.userId = m.userId LEFT JOIN cloud_users c ON c.localUserId=u.userId AND c.serverUrl=(SELECT serverUrl FROM cloud_households WHERE localHouseholdId=m.householdId) WHERE m.householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS + " ORDER BY CASE m.role WHEN 'Owner' THEN 0 ELSE 1 END, CASE m.status WHEN 'Invited' THEN 1 ELSE 0 END, u.displayName, u.userId")
    @NonNull
    public abstract Flow<List<HouseholdMemberProfile>> observeMembers();

    @Query("SELECT * FROM household_settings WHERE householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS)
    @NonNull
    public abstract Flow<HouseholdSettingEntity> observeSettings();

    @Query("SELECT expiryWarningDays FROM household_settings WHERE householdId = " + ScopeSql.HOUSEHOLD + " AND EXISTS (SELECT 1 FROM household_members m WHERE m.householdId = " + ScopeSql.HOUSEHOLD + " AND m.userId = " + ScopeSql.USER + " AND m.status = 'Active')")
    @NonNull
    public abstract Flow<Integer> observeScopedWarningDays();

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract void initializeSettingsNow(@NonNull HouseholdSettingEntity settings);

    @NonNull
    public final Object initializeSettings(@NonNull HouseholdSettingEntity settings,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> initializeSettingsNow(settings), continuation);
    }

    @WorkerThread
    @Query("UPDATE household_settings SET expiryWarningDays = MAX(1, MIN(30, expiryWarningDays + :delta)) WHERE householdId = :expectedHouseholdId AND householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS)
    public abstract int adjustWarningDaysNow(@NonNull String expectedHouseholdId, int delta);

    @NonNull
    public final Object adjustWarningDays(@NonNull String expectedHouseholdId, int delta,
            @NonNull Continuation<? super Integer> continuation) {
        return CoroutinesRoom.execute(database, true, () -> adjustWarningDaysNow(expectedHouseholdId, delta), continuation);
    }

    @WorkerThread
    @Query("UPDATE household_settings SET expiryWarningDays = :days WHERE householdId = " + ScopeSql.HOUSEHOLD + " AND EXISTS (SELECT 1 FROM household_members m WHERE m.householdId = " + ScopeSql.HOUSEHOLD + " AND m.userId = " + ScopeSql.USER + " AND m.status = 'Active')")
    public abstract int setScopedWarningDaysNow(int days);

    @NonNull
    public final Object setScopedWarningDays(int days,
            @NonNull Continuation<? super Integer> continuation) {
        return CoroutinesRoom.execute(database, true, () -> setScopedWarningDaysNow(days), continuation);
    }

    @WorkerThread
    @Query("UPDATE households SET name = :name, description = :description WHERE householdId = :expectedHouseholdId AND householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.OWNER)
    public abstract int updateDetailsNow(@NonNull String expectedHouseholdId, @NonNull String name, @NonNull String description);

    @NonNull
    public final Object updateDetails(@NonNull String expectedHouseholdId, @NonNull String name, @NonNull String description,
            @NonNull Continuation<? super Integer> continuation) {
        return CoroutinesRoom.execute(database, true, () -> updateDetailsNow(expectedHouseholdId, name, description), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM household_members WHERE householdId = :householdId AND userId = :userId")
    @Nullable
    public abstract HouseholdMemberEntity membershipNow(@NonNull String householdId, @NonNull String userId);

    @Nullable
    public final Object membership(@NonNull String householdId, @NonNull String userId,
            @NonNull Continuation<? super HouseholdMemberEntity> continuation) {
        return CoroutinesRoom.execute(database, false, () -> membershipNow(householdId, userId), continuation);
    }

    @WorkerThread
    @Query("UPDATE household_members SET status = 'Active', lastSeenAt = :now WHERE householdId = :householdId AND userId = :userId")
    public abstract void acceptMembershipNow(@NonNull String householdId, @NonNull String userId, long now);

    @NonNull
    public final Object acceptMembership(@NonNull String householdId, @NonNull String userId, long now,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> acceptMembershipNow(householdId, userId, now), continuation);
    }

    @WorkerThread
    @Query("UPDATE household_members SET lastSeenAt = :now WHERE userId = :userId AND status = 'Active'")
    public abstract void touchMemberNow(@NonNull String userId, long now);

    @NonNull
    public final Object touchMember(@NonNull String userId, long now,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> touchMemberNow(userId, now), continuation);
    }

    @Nullable
    private Object executeWrite(Runnable statement, Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(database, true, () -> {
            statement.run();
            return Unit.INSTANCE;
        }, continuation);
    }
}

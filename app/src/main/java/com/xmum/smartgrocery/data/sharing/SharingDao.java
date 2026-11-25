package com.xmum.smartgrocery.data.sharing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.room.CoroutinesRoom;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Update;

import com.xmum.smartgrocery.data.local.dao.HouseholdSql;

import com.xmum.smartgrocery.data.local.entity.BudgetEntry;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;
import com.xmum.smartgrocery.data.local.entity.InventoryLog;
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;
import com.xmum.smartgrocery.data.local.entity.UserEntity;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Dao
public abstract class SharingDao {
    private final RoomDatabase database;

    protected SharingDao(@NonNull RoomDatabase database) {
        this.database = database;
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void accountNow(@NonNull CloudAccount value);

    @NonNull
    public final Object account(@NonNull CloudAccount value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> accountNow(value), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM cloud_accounts WHERE localUserId=:userId AND serverUrl=:server")
    @Nullable
    public abstract CloudAccount accountNow(@NonNull String userId, @NonNull String server);

    @Nullable
    public final Object account(@NonNull String userId, @NonNull String server,
            @NonNull Continuation<? super CloudAccount> continuation) {
        return CoroutinesRoom.execute(database, false, () -> accountNow(userId, server), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM cloud_accounts WHERE localUserId=:userId AND serverUrl=:server")
    public abstract void forgetAccountNow(@NonNull String userId, @NonNull String server);

    @NonNull
    public final Object forgetAccount(@NonNull String userId, @NonNull String server,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> forgetAccountNow(userId, server), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void userNow(@NonNull CloudUser value);

    @NonNull
    public final Object user(@NonNull CloudUser value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> userNow(value), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM cloud_users WHERE serverUrl=:server AND remoteUserId=:remoteId")
    @Nullable
    public abstract CloudUser userNow(@NonNull String server, @NonNull String remoteId);

    @Nullable
    public final Object user(@NonNull String server, @NonNull String remoteId,
            @NonNull Continuation<? super CloudUser> continuation) {
        return CoroutinesRoom.execute(database, false, () -> userNow(server, remoteId), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM cloud_users WHERE localUserId=:localUserId AND serverUrl=:server LIMIT 1")
    @Nullable
    public abstract CloudUser userForLocalNow(@NonNull String localUserId, @NonNull String server);

    @Nullable
    public final Object userForLocal(@NonNull String localUserId, @NonNull String server,
            @NonNull Continuation<? super CloudUser> continuation) {
        return CoroutinesRoom.execute(database, false, () -> userForLocalNow(localUserId, server), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM users WHERE userId=:userId")
    @Nullable
    public abstract UserEntity localUserNow(@NonNull String userId);

    @Nullable
    public final Object localUser(@NonNull String userId,
            @NonNull Continuation<? super UserEntity> continuation) {
        return CoroutinesRoom.execute(database, false, () -> localUserNow(userId), continuation);
    }

    @WorkerThread
    @Update
    public abstract void updateUserNow(@NonNull UserEntity user);

    @NonNull
    public final Object updateUser(@NonNull UserEntity user,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> updateUserNow(user), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void householdNow(@NonNull CloudHousehold value);

    @NonNull
    public final Object household(@NonNull CloudHousehold value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> householdNow(value), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM cloud_households WHERE localHouseholdId=:id")
    @Nullable
    public abstract CloudHousehold householdNow(@NonNull String id);

    @Nullable
    public final Object household(@NonNull String id,
            @NonNull Continuation<? super CloudHousehold> continuation) {
        return CoroutinesRoom.execute(database, false, () -> householdNow(id), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM cloud_households WHERE serverUrl=:server AND remoteHouseholdId=:id")
    @Nullable
    public abstract CloudHousehold householdNow(@NonNull String server, @NonNull String id);

    @Nullable
    public final Object household(@NonNull String server, @NonNull String id,
            @NonNull Continuation<? super CloudHousehold> continuation) {
        return CoroutinesRoom.execute(database, false, () -> householdNow(server, id), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM cloud_households WHERE serverUrl=:server")
    @NonNull
    public abstract List<CloudHousehold> householdsNow(@NonNull String server);

    @NonNull
    public final Object households(@NonNull String server,
            @NonNull Continuation<? super List<CloudHousehold>> continuation) {
        return CoroutinesRoom.execute(database, false, () -> householdsNow(server), continuation);
    }

    @Query("SELECT * FROM cloud_households WHERE localHouseholdId=" + HouseholdSql.ID)
    @NonNull
    public abstract Flow<CloudHousehold> activeHousehold();

    @WorkerThread
    @Query("SELECT * FROM sync_entities WHERE householdId=:id")
    @NonNull
    public abstract List<SyncEntity> marksNow(@NonNull String id);

    @NonNull
    public final Object marks(@NonNull String id,
            @NonNull Continuation<? super List<SyncEntity>> continuation) {
        return CoroutinesRoom.execute(database, false, () -> marksNow(id), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void markNow(@NonNull SyncEntity value);

    @NonNull
    public final Object mark(@NonNull SyncEntity value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> markNow(value), continuation);
    }

    @Query("SELECT * FROM sync_entities WHERE dirty=1")
    @NonNull
    public abstract Flow<List<SyncEntity>> pending();

    @WorkerThread
    @Query("UPDATE sync_control SET applying=:applying WHERE id=1")
    public abstract void applyingNow(boolean applying);

    @NonNull
    public final Object applying(boolean applying,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> applyingNow(applying), continuation);
    }

    @WorkerThread
    @Query("SELECT deviceId FROM sync_control WHERE id=1")
    @NonNull
    public abstract String deviceIdNow();

    @NonNull
    public final Object deviceId(@NonNull Continuation<? super String> continuation) {
        return CoroutinesRoom.execute(database, false, this::deviceIdNow, continuation);
    }

    @WorkerThread
    @Insert
    public abstract void enqueueNow(@NonNull PendingOperation operation);

    @NonNull
    public final Object enqueue(@NonNull PendingOperation operation,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> enqueueNow(operation), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM sync_outbox WHERE householdId=:id ORDER BY mutationId")
    @NonNull
    public abstract List<PendingOperation> outboxNow(@NonNull String id);

    @NonNull
    public final Object outbox(@NonNull String id,
            @NonNull Continuation<? super List<PendingOperation>> continuation) {
        return CoroutinesRoom.execute(database, false, () -> outboxNow(id), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM sync_outbox WHERE mutationId IN (:ids)")
    public abstract void acknowledgeNow(@NonNull List<String> ids);

    @NonNull
    public final Object acknowledge(@NonNull List<String> ids,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> acknowledgeNow(ids), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM sync_outbox WHERE householdId=:id")
    public abstract void discardOutboxNow(@NonNull String id);

    @NonNull
    public final Object discardOutbox(@NonNull String id,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> discardOutboxNow(id), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract long insertInventoryNow(@NonNull GroceryItem value);

    @NonNull
    public final Object insertInventory(@NonNull GroceryItem value,
            @NonNull Continuation<? super Long> continuation) {
        return CoroutinesRoom.execute(database, true, () -> insertInventoryNow(value), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract long insertShoppingNow(@NonNull ShoppingListItem value);

    @NonNull
    public final Object insertShopping(@NonNull ShoppingListItem value,
            @NonNull Continuation<? super Long> continuation) {
        return CoroutinesRoom.execute(database, true, () -> insertShoppingNow(value), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract long insertLogNow(@NonNull InventoryLog value);

    @NonNull
    public final Object insertLog(@NonNull InventoryLog value,
            @NonNull Continuation<? super Long> continuation) {
        return CoroutinesRoom.execute(database, true, () -> insertLogNow(value), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void budgetNow(@NonNull BudgetEntry value);

    @NonNull
    public final Object budget(@NonNull BudgetEntry value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> budgetNow(value), continuation);
    }

    @WorkerThread
    @Update
    public abstract void inventoryNow(@NonNull GroceryItem value);

    @NonNull
    public final Object inventory(@NonNull GroceryItem value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> inventoryNow(value), continuation);
    }

    @WorkerThread
    @Update
    public abstract void shoppingNow(@NonNull ShoppingListItem value);

    @NonNull
    public final Object shopping(@NonNull ShoppingListItem value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> shoppingNow(value), continuation);
    }

    @WorkerThread
    @Update
    public abstract void householdNow(@NonNull HouseholdEntity value);

    @NonNull
    public final Object household(@NonNull HouseholdEntity value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> householdNow(value), continuation);
    }

    @WorkerThread
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void settingsNow(@NonNull HouseholdSettingEntity value);

    @NonNull
    public final Object settings(@NonNull HouseholdSettingEntity value,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> settingsNow(value), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM grocery_items WHERE householdId=:id AND id=:key")
    @Nullable
    public abstract GroceryItem inventoryNow(@NonNull String id, int key);

    @Nullable
    public final Object inventory(@NonNull String id, int key,
            @NonNull Continuation<? super GroceryItem> continuation) {
        return CoroutinesRoom.execute(database, false, () -> inventoryNow(id, key), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM shopping_list_items WHERE householdId=:id AND id=:key")
    @Nullable
    public abstract ShoppingListItem shoppingNow(@NonNull String id, int key);

    @Nullable
    public final Object shopping(@NonNull String id, int key,
            @NonNull Continuation<? super ShoppingListItem> continuation) {
        return CoroutinesRoom.execute(database, false, () -> shoppingNow(id, key), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM shopping_list_items WHERE householdId=:id AND sourceItemId=:sourceId")
    @Nullable
    public abstract ShoppingListItem shoppingBySourceNow(@NonNull String id, int sourceId);

    @Nullable
    public final Object shoppingBySource(@NonNull String id, int sourceId,
            @NonNull Continuation<? super ShoppingListItem> continuation) {
        return CoroutinesRoom.execute(database, false, () -> shoppingBySourceNow(id, sourceId), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM inventory_logs WHERE householdId=:id AND id=:key")
    @Nullable
    public abstract InventoryLog logNow(@NonNull String id, int key);

    @Nullable
    public final Object log(@NonNull String id, int key,
            @NonNull Continuation<? super InventoryLog> continuation) {
        return CoroutinesRoom.execute(database, false, () -> logNow(id, key), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM budget_entries WHERE householdId=:id AND monthYear=:key")
    @Nullable
    public abstract BudgetEntry budgetNow(@NonNull String id, @NonNull String key);

    @Nullable
    public final Object budget(@NonNull String id, @NonNull String key,
            @NonNull Continuation<? super BudgetEntry> continuation) {
        return CoroutinesRoom.execute(database, false, () -> budgetNow(id, key), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM households WHERE householdId=:id")
    @Nullable
    public abstract HouseholdEntity localHouseholdNow(@NonNull String id);

    @Nullable
    public final Object localHousehold(@NonNull String id,
            @NonNull Continuation<? super HouseholdEntity> continuation) {
        return CoroutinesRoom.execute(database, false, () -> localHouseholdNow(id), continuation);
    }

    @WorkerThread
    @Query("SELECT * FROM household_settings WHERE householdId=:id")
    @Nullable
    public abstract HouseholdSettingEntity settingsNow(@NonNull String id);

    @Nullable
    public final Object settings(@NonNull String id,
            @NonNull Continuation<? super HouseholdSettingEntity> continuation) {
        return CoroutinesRoom.execute(database, false, () -> settingsNow(id), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM grocery_items WHERE householdId=:id AND id=:key")
    public abstract void deleteInventoryNow(@NonNull String id, int key);

    @NonNull
    public final Object deleteInventory(@NonNull String id, int key,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteInventoryNow(id, key), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM shopping_list_items WHERE householdId=:id AND id=:key")
    public abstract void deleteShoppingNow(@NonNull String id, int key);

    @NonNull
    public final Object deleteShopping(@NonNull String id, int key,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteShoppingNow(id, key), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM budget_entries WHERE householdId=:id AND monthYear=:key")
    public abstract void deleteBudgetNow(@NonNull String id, @NonNull String key);

    @NonNull
    public final Object deleteBudget(@NonNull String id, @NonNull String key,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteBudgetNow(id, key), continuation);
    }

    @WorkerThread
    @Query("DELETE FROM household_members WHERE householdId=:id")
    public abstract void clearMembersNow(@NonNull String id);

    @NonNull
    public final Object clearMembers(@NonNull String id,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> clearMembersNow(id), continuation);
    }

    @WorkerThread
    @Query("SELECT COUNT(*) FROM household_members WHERE householdId=:id AND userId=:userId AND status='Active'")
    public abstract int activeNow(@NonNull String id, @NonNull String userId);

    @NonNull
    public final Object active(@NonNull String id, @NonNull String userId,
            @NonNull Continuation<? super Integer> continuation) {
        return CoroutinesRoom.execute(database, false, () -> activeNow(id, userId), continuation);
    }

    @WorkerThread
    @Query("UPDATE sync_entities SET dirty=0 WHERE householdId=:id")
    public abstract void discardPendingNow(@NonNull String id);

    @NonNull
    public final Object discardPending(@NonNull String id,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> discardPendingNow(id), continuation);
    }

    @Nullable
    private Object executeWrite(Runnable statement, Continuation<? super Unit> continuation) {
        return CoroutinesRoom.execute(database, true, () -> {
            statement.run();
            return Unit.INSTANCE;
        }, continuation);
    }
}

package com.xmum.smartgrocery.data.sharing

import androidx.room.*
import com.xmum.smartgrocery.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SharingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun account(value: CloudAccount)
    @Query("SELECT * FROM cloud_accounts WHERE localUserId=:userId AND serverUrl=:server") suspend fun account(userId: String, server: String): CloudAccount?
    @Query("DELETE FROM cloud_accounts WHERE localUserId=:userId AND serverUrl=:server") suspend fun forgetAccount(userId: String, server: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun user(value: CloudUser)
    @Query("SELECT * FROM cloud_users WHERE serverUrl=:server AND remoteUserId=:remoteId") suspend fun user(server: String, remoteId: String): CloudUser?
    @Query("SELECT * FROM cloud_users WHERE localUserId=:localUserId AND serverUrl=:server LIMIT 1") suspend fun userForLocal(localUserId: String, server: String): CloudUser?
    @Query("SELECT * FROM users WHERE userId=:userId") suspend fun localUser(userId: String): UserEntity?
    @Update suspend fun updateUser(user: UserEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun household(value: CloudHousehold)
    @Query("SELECT * FROM cloud_households WHERE localHouseholdId=:id") suspend fun household(id: String): CloudHousehold?
    @Query("SELECT * FROM cloud_households WHERE serverUrl=:server AND remoteHouseholdId=:id") suspend fun household(server: String, id: String): CloudHousehold?
    @Query("SELECT * FROM cloud_households WHERE serverUrl=:server") suspend fun households(server: String): List<CloudHousehold>
    @Query("SELECT * FROM cloud_households WHERE localHouseholdId=" + com.xmum.smartgrocery.data.local.dao.HouseholdSql.ID) fun activeHousehold(): Flow<CloudHousehold?>
    @Query("SELECT * FROM sync_entities WHERE householdId=:id") suspend fun marks(id: String): List<SyncEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun mark(value: SyncEntity)
    @Query("SELECT * FROM sync_entities WHERE dirty=1") fun pending(): Flow<List<SyncEntity>>
    @Query("UPDATE sync_control SET applying=:applying WHERE id=1") suspend fun applying(applying: Boolean)
    @Query("SELECT deviceId FROM sync_control WHERE id=1") suspend fun deviceId(): String
    @Insert suspend fun enqueue(operation: PendingOperation)
    @Query("SELECT * FROM sync_outbox WHERE householdId=:id ORDER BY mutationId") suspend fun outbox(id: String): List<PendingOperation>
    @Query("DELETE FROM sync_outbox WHERE mutationId IN (:ids)") suspend fun acknowledge(ids: List<String>)
    @Query("DELETE FROM sync_outbox WHERE householdId=:id") suspend fun discardOutbox(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInventory(value: GroceryItem): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertShopping(value: ShoppingListItem): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLog(value: InventoryLog): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun budget(value: BudgetEntry)
    @Update suspend fun inventory(value: GroceryItem)
    @Update suspend fun shopping(value: ShoppingListItem)
    @Update suspend fun household(value: HouseholdEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun settings(value: HouseholdSettingEntity)
    @Query("SELECT * FROM grocery_items WHERE householdId=:id AND id=:key") suspend fun inventory(id: String, key: Int): GroceryItem?
    @Query("SELECT * FROM shopping_list_items WHERE householdId=:id AND id=:key") suspend fun shopping(id: String, key: Int): ShoppingListItem?
    @Query("SELECT * FROM shopping_list_items WHERE householdId=:id AND sourceItemId=:sourceId") suspend fun shoppingBySource(id: String, sourceId: Int): ShoppingListItem?
    @Query("SELECT * FROM inventory_logs WHERE householdId=:id AND id=:key") suspend fun log(id: String, key: Int): InventoryLog?
    @Query("SELECT * FROM budget_entries WHERE householdId=:id AND monthYear=:key") suspend fun budget(id: String, key: String): BudgetEntry?
    @Query("SELECT * FROM households WHERE householdId=:id") suspend fun localHousehold(id: String): HouseholdEntity?
    @Query("SELECT * FROM household_settings WHERE householdId=:id") suspend fun settings(id: String): HouseholdSettingEntity?
    @Query("DELETE FROM grocery_items WHERE householdId=:id AND id=:key") suspend fun deleteInventory(id: String, key: Int)
    @Query("DELETE FROM shopping_list_items WHERE householdId=:id AND id=:key") suspend fun deleteShopping(id: String, key: Int)
    @Query("DELETE FROM budget_entries WHERE householdId=:id AND monthYear=:key") suspend fun deleteBudget(id: String, key: String)
    @Query("DELETE FROM household_members WHERE householdId=:id") suspend fun clearMembers(id: String)
    @Query("SELECT COUNT(*) FROM household_members WHERE householdId=:id AND userId=:userId AND status='Active'") suspend fun active(id: String, userId: String): Int
    @Query("UPDATE sync_entities SET dirty=0 WHERE householdId=:id") suspend fun discardPending(id: String)
}

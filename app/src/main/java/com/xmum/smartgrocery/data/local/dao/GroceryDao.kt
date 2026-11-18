package com.xmum.smartgrocery.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.xmum.smartgrocery.data.local.entity.InventoryChangeType
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import kotlinx.coroutines.flow.Flow

@Dao
abstract class GroceryDao : ScopedDao() {
    @Query("SELECT displayName FROM users WHERE userId = " + ScopeSql.USER)
    protected abstract suspend fun actorName(): String?

    @Query("SELECT * FROM grocery_items WHERE " + ScopeSql.ACCESSIBLE + " ORDER BY id")
    abstract fun observeAccessibleItems(): Flow<List<GroceryItem>>

    @Query("SELECT * FROM grocery_items WHERE " + ScopeSql.MATCH + " ORDER BY purchasedDate DESC")
    abstract fun getAllItems(): Flow<List<GroceryItem>>

    @Query("SELECT * FROM grocery_items WHERE " + ScopeSql.MATCH + " ORDER BY purchasedDate DESC")
    abstract suspend fun getAllItemsOnce(): List<GroceryItem>

    @Query("SELECT * FROM grocery_items WHERE (name LIKE :name) AND " + ScopeSql.MATCH + " LIMIT 1")
    abstract suspend fun getItemByName(name: String): GroceryItem?

    @Query("SELECT * FROM grocery_items WHERE (LOWER(name) LIKE '%' || LOWER(:name) || '%' OR LOWER(:name) LIKE '%' || LOWER(name) || '%') AND " + ScopeSql.MATCH + " ORDER BY quantity DESC LIMIT 1")
    abstract suspend fun findBestMatchingItem(name: String): GroceryItem?

    @Query("SELECT * FROM grocery_items WHERE (LOWER(name) LIKE '%' || LOWER(:name) || '%' OR LOWER(:name) LIKE '%' || LOWER(name) || '%') AND " + ScopeSql.MATCH + " ORDER BY CASE WHEN expiryDate IS NULL OR expiryDate <= 0 THEN 1 ELSE 0 END, expiryDate ASC, id ASC")
    abstract suspend fun findMatchingItems(name: String): List<GroceryItem>

    @Query("SELECT * FROM grocery_items WHERE (id = :id) AND " + ScopeSql.MATCH + " LIMIT 1")
    abstract suspend fun getItemById(id: Int): GroceryItem?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRaw(item: GroceryItem): Long

    @Transaction
    open suspend fun insert(item: GroceryItem): Long {
        val scope = requireScope()
        require(item.id == 0) { "Use update for existing inventory." }
        scope.requireDraft(item.userId, item.householdId)
        val actor = if (scope.householdId != null) actorName() else null
        return insertRaw(item.normalized().copy(userId = scope.userId, householdId = scope.householdId,
            addedByName = actor, lastModifiedByName = actor, lastModifiedAction = actor?.let { "Added" },
            lastModifiedAt = actor?.let { System.currentTimeMillis() }))
    }

    @Insert
    protected abstract suspend fun insertLogRaw(log: InventoryLog): Long

    @Transaction
    open suspend fun insertLog(log: InventoryLog): Long {
        val item = log.itemId?.let { getItemById(it) }
        require(item != null) { "Inventory logs must reference accessible inventory." }
        return insertLogRaw(log.copy(userId = item.userId, householdId = item.householdId))
    }

    @Transaction
    open suspend fun insertWithLog(item: GroceryItem): Long {
        val id = insert(item).toInt()
        if (item.quantity > 0) {
            insertLog(
                InventoryLog(
                    itemId = id,
                    itemName = item.name,
                    category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(item.category).label,
                    changeType = InventoryChangeType.ADD,
                    amountChanged = item.quantity,
                    unitPriceAtChange = item.price,
                    timestamp = item.purchasedDate
                )
            )
        }
        return id.toLong()
    }

    @Update
    protected abstract suspend fun updateRaw(item: GroceryItem): Int

    @Transaction
    open suspend fun update(item: GroceryItem): Int {
        val old = getItemById(item.id) ?: return 0
        require(item.userId == old.userId && item.householdId == old.householdId) { "Ownership cannot be changed by an edit." }
        
        val actor = if (old.householdId != null) actorName() else null
        return updateRaw(item.normalized().copy(addedByName = old.addedByName, lastModifiedByName = actor,
            lastModifiedAction = actor?.let { "Updated" }, lastModifiedAt = actor?.let { System.currentTimeMillis() }))
    }

    /** One outer Room transaction covers every item and its existing inventory log. */
    @Transaction
    open suspend fun insertBatchWithLogs(items: List<GroceryItem>): List<Long> {
        require(items.isNotEmpty()) { "Select at least one item." }
        require(items.all { it.id == 0 && it.name.isNotBlank() && it.quantity > 0 && it.price.isFinite() && it.price >= 0.0 }) {
            "Review all item names and quantities before adding."
        }
        return items.map { insertWithLog(it) }
    }

    @Query("DELETE FROM grocery_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract suspend fun deleteScoped(id: Int): Int

    open suspend fun delete(item: GroceryItem): Int = deleteScoped(item.id)

    @Query("DELETE FROM grocery_items WHERE (expiryDate > 0 AND expiryDate < :today) AND " + ScopeSql.MATCH)
    abstract suspend fun deleteExpiredItems(today: Long): Int

    @Query("UPDATE grocery_items SET quantity = :newQty, lastModifiedByName = CASE WHEN householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedAction = CASE WHEN householdId IS NOT NULL THEN 'Updated' ELSE NULL END, lastModifiedAt = CASE WHEN householdId IS NOT NULL THEN :modifiedAt ELSE NULL END WHERE (id = :id) AND " + ScopeSql.MATCH)
    abstract suspend fun updateQuantity(id: Int, newQty: Int, modifiedAt: Long = System.currentTimeMillis())

    @Transaction
    open suspend fun updateQuantityWithLog(
        id: Int,
        newQty: Int,
        changeType: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val item = getItemById(id) ?: return
        val safeQty = newQty.coerceAtLeast(0)
        val delta = safeQty - item.quantity
        updateQuantity(id, safeQty)
        if (delta != 0) {
            insertLog(
                InventoryLog(
                    itemId = id,
                    itemName = item.name,
                    category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(item.category).label,
                    changeType = changeType ?: if (delta > 0) InventoryChangeType.ADD else InventoryChangeType.DEDUCT,
                    amountChanged = kotlin.math.abs(delta),
                    unitPriceAtChange = item.price,
                    timestamp = timestamp
                )
            )
        }
    }

    @Transaction
    open suspend fun deductRecipeItems(updates: List<RecipeDeductUpdate>) {
        updates.forEach { update ->
            deductQuantity(update.itemId, update.quantityUsed, InventoryChangeType.RECIPE_DEDUCT)
        }
    }

    @Query("UPDATE grocery_items SET quantity = CASE WHEN quantity - :quantityUsed < 0 THEN 0 ELSE quantity - :quantityUsed END, lastModifiedByName = CASE WHEN householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedAction = CASE WHEN householdId IS NOT NULL THEN 'Updated' ELSE NULL END, lastModifiedAt = CASE WHEN householdId IS NOT NULL THEN :modifiedAt ELSE NULL END WHERE (id = :itemId) AND " + ScopeSql.MATCH + "")
    abstract suspend fun deductQuantityRaw(itemId: Int, quantityUsed: Int, modifiedAt: Long = System.currentTimeMillis())

    @Transaction
    open suspend fun deductQuantity(
        itemId: Int,
        quantityUsed: Int,
        changeType: String = InventoryChangeType.DEDUCT,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val item = getItemById(itemId) ?: return
        val actualDeduction = quantityUsed.coerceAtLeast(0).coerceAtMost(item.quantity)
        if (actualDeduction <= 0) {
            return
        }
        deductQuantityRaw(itemId, actualDeduction)
        insertLog(
            InventoryLog(
                itemId = itemId,
                itemName = item.name,
                category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(item.category).label,
                changeType = changeType,
                amountChanged = actualDeduction,
                unitPriceAtChange = item.price,
                timestamp = timestamp
            )
        )
    }

    @Query("SELECT * FROM grocery_items WHERE (quantity <= threshold) AND " + ScopeSql.MATCH + "")
    abstract fun getLowStockItems(): Flow<List<GroceryItem>>

    @Query("SELECT * FROM inventory_logs WHERE (itemId = :itemId) AND " + ScopeSql.MATCH + " ORDER BY timestamp DESC")
    abstract fun getLogsForItem(itemId: Int): Flow<List<InventoryLog>>

    /** Historical snapshots survive item deletion and use the same scope as budget spending. */
    @Query("SELECT * FROM inventory_logs WHERE changeType = 'ADD' AND amountChanged > 0 AND " + ScopeSql.MATCH + " ORDER BY timestamp DESC, id DESC")
    abstract fun getPurchaseHistory(): Flow<List<InventoryLog>>
}

data class RecipeDeductUpdate(
    val itemId: Int,
    val quantityUsed: Int
)

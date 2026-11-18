package com.xmum.smartgrocery.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.utils.ShoppingVoiceMerge
import com.xmum.smartgrocery.utils.ChecklistShoppingMerge
import com.xmum.smartgrocery.utils.VoiceCommand
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ShoppingListDao : ScopedDao() {
    @Query("SELECT displayName FROM users WHERE userId = " + ScopeSql.USER)
    protected abstract suspend fun actorName(): String?


    @Query("SELECT * FROM shopping_list_items WHERE " + ScopeSql.MATCH + " ORDER BY isChecked ASC, assignedTo ASC, updatedAt DESC")
    abstract fun getShoppingListItems(): Flow<List<ShoppingListItem>>

    @Query("SELECT * FROM shopping_list_items WHERE (sourceItemId = :sourceItemId) AND " + ScopeSql.MATCH + " LIMIT 1")
    abstract suspend fun getBySourceItemId(sourceItemId: Int): ShoppingListItem?

    @Query("SELECT * FROM shopping_list_items WHERE (sourceItemId IS NULL AND isChecked = 0 AND assignedTo = :assignedTo) AND " + ScopeSql.MATCH)
    abstract suspend fun getOpenManualItemsForAssignee(assignedTo: String): List<ShoppingListItem>

    @Query("SELECT * FROM shopping_list_items WHERE (isChecked = 0 AND assignedTo = :assignedTo) AND " + ScopeSql.MATCH + " ORDER BY id")
    abstract suspend fun getOpenItemsForAssignee(assignedTo: String): List<ShoppingListItem>

    /** Re-read matches within one transaction so repeated/concurrent additions cannot create duplicates. */
    @Transaction
    open suspend fun addVoiceItems(commands: List<VoiceCommand>, assignedTo: String) {
        require(commands.isNotEmpty()) { "Review at least one shopping item." }
        commands.forEach { command ->
            val target = if (requireScope().householdId == null) "Me" else "Household"
            val planned = ShoppingVoiceMerge.merge(getOpenItemsForAssignee(target), command, target)
            if (planned.id == 0) insertManual(planned) else update(planned)
        }
    }

    @Transaction
    open suspend fun addAiChecklistItems(items: List<ChecklistShoppingMerge.Addition>, assignedTo: String) {
        items.forEach { addition ->
            val target = if (requireScope().householdId == null) "Me" else "Household"
            val planned = ChecklistShoppingMerge.merge(getOpenItemsForAssignee(target), addition, target)
            if (planned.id == 0) insertManual(planned) else update(planned)
        }
    }

    @Transaction
    open suspend fun insert(item: ShoppingListItem): Long = insertManual(item)

    @Insert
    protected abstract suspend fun insertManualRaw(item: ShoppingListItem): Long

    @Transaction
    open suspend fun insertManual(item: ShoppingListItem): Long {
        val scope = requireScope()
        require(item.id == 0) { "Use update for existing shopping items." }
        scope.requireDraft(item.userId, item.householdId)
        require(item.assignedTo != "Me" || scope.householdId == null) { "Select personal context for a private shopping item." }
        item.sourceItemId?.let { require(validSource(it) != null) { "Source inventory is outside the selected scope." } }
        val actor = if (scope.householdId != null) actorName() else null
        return insertManualRaw(item.normalized().copy(userId = scope.userId, householdId = scope.householdId,
            addedByName = actor, lastModifiedByName = actor, lastModifiedAction = actor?.let { if (item.isChecked) "Checked" else "Added" },
            lastModifiedAt = actor?.let { System.currentTimeMillis() },
            assignedTo = if (scope.householdId == null) "Me" else "Household"))
    }

    @Query("SELECT id FROM grocery_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract suspend fun validSource(id: Int): Int?

    @Query("SELECT * FROM shopping_list_items WHERE id = :id AND " + ScopeSql.MATCH)
    abstract suspend fun getById(id: Int): ShoppingListItem?

    @Update
    protected abstract suspend fun updateRaw(item: ShoppingListItem): Int

    @Transaction
    open suspend fun update(item: ShoppingListItem): Int {
        val old = getById(item.id) ?: return 0
        require(item.userId == old.userId && item.householdId == old.householdId) { "Ownership cannot be changed by an edit." }
        require(item.sourceItemId == null || item.sourceItemId == old.sourceItemId) { "Cannot change inventory source." }
        val actor = if (old.householdId != null) actorName() else null
        val action = if (old.isChecked != item.isChecked) { if (item.isChecked) "Checked" else "Unchecked" } else "Updated"
        return updateRaw(item.normalized().copy(addedByName = old.addedByName, lastModifiedByName = actor,
            lastModifiedAction = actor?.let { action }, lastModifiedAt = actor?.let { System.currentTimeMillis() }))
    }

    @Query("DELETE FROM shopping_list_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract suspend fun deleteScoped(id: Int): Int

    open suspend fun delete(item: ShoppingListItem): Int = deleteScoped(item.id)

    @Query("UPDATE shopping_list_items SET isChecked = :isChecked, updatedAt = :updatedAt, lastModifiedByName = CASE WHEN householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedAction = CASE WHEN householdId IS NOT NULL THEN CASE WHEN :isChecked THEN 'Checked' ELSE 'Unchecked' END ELSE NULL END, lastModifiedAt = CASE WHEN householdId IS NOT NULL THEN :updatedAt ELSE NULL END WHERE (id = :id) AND " + ScopeSql.MATCH)
    abstract suspend fun setChecked(id: Int, isChecked: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM shopping_list_items WHERE id = :id AND " + ScopeSql.ACCESSIBLE)
    protected abstract suspend fun accessibleById(id: Int): ShoppingListItem?

    @Query("UPDATE shopping_list_items SET addedByName = CASE WHEN :householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedByName = CASE WHEN :householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedAction = CASE WHEN :householdId IS NOT NULL THEN 'Added' ELSE NULL END, lastModifiedAt = CASE WHEN :householdId IS NOT NULL THEN :updatedAt ELSE NULL END, userId = :userId, householdId = :householdId, sourceItemId = NULL, assignedTo = :assignedTo, updatedAt = :updatedAt WHERE id = :id AND " + ScopeSql.ACCESSIBLE)
    protected abstract suspend fun moveToScope(id: Int, userId: String, householdId: String?, assignedTo: String, updatedAt: Long)

    @Transaction
    open suspend fun assignTo(id: Int, assignedTo: String, updatedAt: Long = System.currentTimeMillis()) {
        val item = accessibleById(id) ?: return
        val scope = requireScope()
        require(assignedTo == if (scope.householdId == null) "Me" else "Household") { "Select the target data context first." }
        if (item.householdId != scope.householdId) {
            // An explicit transfer drops the old inventory link; private stock is never shared implicitly.
            moveToScope(id, scope.userId, scope.householdId, assignedTo, updatedAt)
        }
    }

    @Query("DELETE FROM shopping_list_items WHERE (isChecked = 1) AND " + ScopeSql.MATCH)
    abstract suspend fun deleteChecked()

    @Query("DELETE FROM shopping_list_items WHERE (sourceItemId = :sourceItemId) AND " + ScopeSql.MATCH)
    abstract suspend fun deleteBySourceItemId(sourceItemId: Int)

    /** Compatibility refresh: suggestions never create or modify an approved shopping plan. */
    @Transaction
    open suspend fun syncLowStockItems(items: List<GroceryItem>, expectedScope: DataScope? = null) {
        val scope = activeScope() ?: return
        if (expectedScope != null && scope != expectedScope) return
        removeUnapprovedRestocks()
    }

    @Query("DELETE FROM shopping_list_items WHERE addedBy = 'Auto' AND sourceItemId IS NOT NULL AND isChecked = 0 AND " + ScopeSql.ACCESSIBLE)
    abstract suspend fun removeUnapprovedRestocks(): Int

    @Query("SELECT * FROM grocery_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract suspend fun restockSource(id: Int): GroceryItem?

    @Query("SELECT COUNT(*) FROM shopping_list_items WHERE addedBy = 'Auto' AND sourceItemId IS NOT NULL AND isChecked = 0 AND " + ScopeSql.ACCESSIBLE)
    abstract fun observeUnapprovedRestockCount(): Flow<Int>

    /** Only the explicit review button calls this transaction. Re-read stock and plans before writing. */
    @Transaction
    open suspend fun approveRestocks(quantities: Map<Int, Int>): Int {
        val scope = requireScope()
        var added = 0
        quantities.forEach { (id, quantity) ->
            require(quantity in 1..9999) { "Choose a restock quantity from 1 to 9999." }
            val item = restockSource(id) ?: return@forEach
            if (item.quantity > item.threshold) return@forEach
            val assignee = if (scope.householdId == null) "Me" else "Household"
            val previous = getBySourceItemId(id)
            if (previous?.isChecked == true) update(previous.copy(sourceItemId = null))
            if ((previous != null && !previous.isChecked) || getOpenItemsForAssignee(assignee).any {
                it.itemName.trim().equals(item.name.trim(), ignoreCase = true)
            }) return@forEach
            insertManual(ShoppingListItem(sourceItemId = id, itemName = item.name,
                category = item.category, subcategory = item.subcategory, quantityNeeded = quantity,
                estimatedUnitPrice = item.price, addedBy = "Restock", assignedTo = assignee))
            added++
        }
        return added
    }

    @Query("SELECT sourceItemId FROM shopping_list_items WHERE (sourceItemId IS NOT NULL) AND " + ScopeSql.MATCH)
    abstract suspend fun getAutoSourceIds(): List<Int>

}

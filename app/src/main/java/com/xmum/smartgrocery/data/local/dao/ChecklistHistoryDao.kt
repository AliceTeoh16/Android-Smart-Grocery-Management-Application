package com.xmum.smartgrocery.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChecklistHistoryDao : ScopedDao() {

    @Query("SELECT * FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at DESC, id DESC LIMIT 10")
    abstract fun getAllChecklistHistory(): Flow<List<ChecklistHistoryItem>>

    @Query("SELECT * FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at DESC, id DESC LIMIT 10")
    abstract suspend fun getAllChecklistHistorySync(): List<ChecklistHistoryItem>

    @Query("SELECT * FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id = :id LIMIT 1")
    abstract suspend fun getChecklistHistoryById(id: String): ChecklistHistoryItem?

    @Query("SELECT COUNT(*) FROM checklist_history WHERE userId = " + ScopeSql.USER)
    abstract suspend fun getCount(): Int

    @Insert
    protected abstract suspend fun insertRaw(item: ChecklistHistoryItem)

    @Transaction
    open suspend fun insert(item: ChecklistHistoryItem) {
        val scope = requireScope()
        scope.requireDraft(item.userId)
        val existing = getChecklistHistoryById(item.id)
        val owned = item.copy(userId = scope.userId)
        if (existing == null) insertRaw(owned) else updateRaw(owned)
    }

    @Transaction
    open suspend fun update(item: ChecklistHistoryItem) {
        val existing = getChecklistHistoryById(item.id) ?: return
        require(item.userId == existing.userId) { "Cannot change history ownership." }
        updateRaw(item)
    }

    @Transaction
    open suspend fun saveWithPruning(item: ChecklistHistoryItem, maxLimit: Int = 10) {
        insert(item)
        pruneToLatest(maxLimit.coerceIn(1, 10))
    }

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id NOT IN (SELECT id FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at DESC, id DESC LIMIT :maxLimit)")
    abstract suspend fun pruneToLatest(maxLimit: Int = 10)

    @Query("UPDATE checklist_history SET stateJson = :stateJson, addedShoppingKeysJson = :addedShoppingKeysJson, status = COALESCE(:status, status) WHERE userId = " + ScopeSql.USER + " AND id = :id")
    abstract suspend fun updateState(id: String, stateJson: String, addedShoppingKeysJson: String, status: String? = null)

    @Query("UPDATE checklist_history SET stateJson = :stateJson WHERE userId = " + ScopeSql.USER + " AND id = :id AND stateJson = ''")
    abstract suspend fun backfillState(id: String, stateJson: String)

    @Update
    protected abstract suspend fun updateRaw(item: ChecklistHistoryItem)

    @Query("UPDATE checklist_history SET status = :status WHERE userId = " + ScopeSql.USER + " AND id = :id")
    abstract suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE checklist_history SET addedShoppingKeysJson = :addedShoppingKeysJson WHERE userId = " + ScopeSql.USER + " AND id = :id")
    abstract suspend fun updateAddedShoppingKeys(id: String, addedShoppingKeysJson: String)

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id IN (SELECT id FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at ASC LIMIT :count)")
    abstract suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER + "")
    abstract suspend fun clearAll()
}

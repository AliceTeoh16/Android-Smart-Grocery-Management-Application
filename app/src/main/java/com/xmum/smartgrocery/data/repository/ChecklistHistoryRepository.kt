package com.xmum.smartgrocery.data.repository

import com.xmum.smartgrocery.data.local.dao.ChecklistHistoryDao
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem
import kotlinx.coroutines.flow.Flow

class ChecklistHistoryRepository(
    private val checklistHistoryDao: ChecklistHistoryDao
) {

    val allHistory: Flow<List<ChecklistHistoryItem>> = checklistHistoryDao.getAllChecklistHistory()

    suspend fun getAllHistorySync(): List<ChecklistHistoryItem> {
        return checklistHistoryDao.getAllChecklistHistorySync()
    }

    suspend fun getById(id: String): ChecklistHistoryItem? {
        return checklistHistoryDao.getChecklistHistoryById(id)
    }

    suspend fun saveChecklistWithPruning(item: ChecklistHistoryItem, maxLimit: Int = 10) {
        checklistHistoryDao.saveWithPruning(item, maxLimit)
    }

    suspend fun pruneToLatest() = checklistHistoryDao.pruneToLatest(10)

    suspend fun backfillState(item: ChecklistHistoryItem) = checklistHistoryDao.backfillState(item.id, item.stateJson)

    suspend fun updateState(item: ChecklistHistoryItem, status: String? = null) =
        checklistHistoryDao.updateState(item.id, item.stateJson, item.addedShoppingKeysJson, status)

    suspend fun updateStatus(id: String, status: String) {
        checklistHistoryDao.updateStatus(id, status)
    }

    suspend fun updateAddedShoppingKeys(id: String, addedShoppingKeysJson: String) {
        checklistHistoryDao.updateAddedShoppingKeys(id, addedShoppingKeysJson)
    }

    suspend fun deleteById(id: String) {
        checklistHistoryDao.deleteById(id)
    }

    suspend fun clearAll() = checklistHistoryDao.clearAll()
}

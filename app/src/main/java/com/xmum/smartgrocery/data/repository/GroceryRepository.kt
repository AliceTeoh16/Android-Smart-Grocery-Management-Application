package com.xmum.smartgrocery.data.repository

import com.xmum.smartgrocery.data.local.dao.GroceryDao
import com.xmum.smartgrocery.data.local.dao.RecipeDeductUpdate
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.InventoryChangeType
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import kotlinx.coroutines.flow.Flow

class GroceryRepository(private val groceryDao: GroceryDao) {

    val allGroceryItems: Flow<List<GroceryItem>> = groceryDao.getAllItems()
    val lowStockItems: Flow<List<GroceryItem>> = groceryDao.getLowStockItems()
    val purchaseHistory: Flow<List<InventoryLog>> = groceryDao.getPurchaseHistory()

    suspend fun insert(item: GroceryItem): Long {
        return groceryDao.insertWithLog(item)
    }

    suspend fun insertBatch(items: List<GroceryItem>): List<Long> = groceryDao.insertBatchWithLogs(items)

    suspend fun update(item: GroceryItem) {
        groceryDao.update(item)
    }

    suspend fun delete(item: GroceryItem) {
        groceryDao.delete(item)
    }

    suspend fun updateQuantity(id: Int, newQty: Int) {
        groceryDao.updateQuantityWithLog(id, newQty)
    }

    suspend fun updateQuantityFromVoiceAdd(id: Int, newQty: Int) {
        groceryDao.updateQuantityWithLog(id, newQty, InventoryChangeType.ADD)
    }

    suspend fun updateQuantityFromVoiceDeduct(id: Int, newQty: Int) {
        groceryDao.updateQuantityWithLog(id, newQty, InventoryChangeType.VOICE_DEDUCT)
    }

    suspend fun deductRecipeItems(updates: List<RecipeDeductUpdate>) {
        groceryDao.deductRecipeItems(updates)
    }

    suspend fun getItemByName(name: String): GroceryItem? {
        return groceryDao.getItemByName(name)
    }

    suspend fun findBestMatchingItem(name: String): GroceryItem? {
        return groceryDao.findBestMatchingItem(name)
    }

    suspend fun findMatchingItems(name: String): List<GroceryItem> {
        return groceryDao.findMatchingItems(name)
    }

    suspend fun consumeQuantityFefo(name: String, quantityUsed: Int) {
        var remaining = quantityUsed.coerceAtLeast(0)
        if (remaining == 0) return

        findMatchingItems(name)
            .filter { it.quantity > 0 }
            .forEach { batch ->
                if (remaining == 0) return@forEach
                val deducted = minOf(batch.quantity, remaining)
                updateQuantity(batch.id, batch.quantity - deducted)
                remaining -= deducted
            }
    }

    suspend fun deleteExpiredItems(today: Long): Int {
        return groceryDao.deleteExpiredItems(today)
    }

    suspend fun getItemById(id: Int): GroceryItem? {
        return groceryDao.getItemById(id)
    }

    suspend fun getAllItemsOnce(): List<GroceryItem> {
        return groceryDao.getAllItemsOnce()
    }

    fun getLogsForItem(itemId: Int): Flow<List<InventoryLog>> {
        return groceryDao.getLogsForItem(itemId)
    }
}

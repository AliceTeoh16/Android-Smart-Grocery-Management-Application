package com.xmum.smartgrocery.data.repository

import com.xmum.smartgrocery.data.local.dao.ShoppingListDao
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.utils.ChecklistShoppingMerge
import com.xmum.smartgrocery.utils.VoiceCommand
import kotlinx.coroutines.flow.Flow

class ShoppingListRepository(private val shoppingListDao: ShoppingListDao, private val sessionManager: SessionManager? = null) {
    private suspend fun <T> inTarget(assignedTo: String, action: suspend () -> T): T =
        if (sessionManager == null) action() else sessionManager.withShoppingScope(assignedTo, action)

    val shoppingListItems: Flow<List<ShoppingListItem>> = shoppingListDao.getShoppingListItems()

    suspend fun syncLowStockItems(items: List<GroceryItem>, expectedScope: com.xmum.smartgrocery.data.local.dao.DataScope? = null) =
        shoppingListDao.syncLowStockItems(items, expectedScope)

    suspend fun addVoiceItems(commands: List<VoiceCommand>, assignedTo: String) = inTarget(assignedTo) {
        shoppingListDao.addVoiceItems(commands, assignedTo)
    }

    suspend fun addManualItem(itemName: String, category: String, quantityNeeded: Int,
        estimatedUnitPrice: Double, assignedTo: String, quantityLabel: String = "") {
        inTarget(assignedTo) {
            shoppingListDao.insertManual(ShoppingListItem(itemName = itemName, category = category,
                quantityNeeded = quantityNeeded.coerceAtLeast(1), quantityLabel = quantityLabel,
                estimatedUnitPrice = estimatedUnitPrice.coerceAtLeast(0.0), addedBy = "Manual", assignedTo = assignedTo))
        }
    }

    suspend fun addAiChecklistItem(itemName: String, category: String, recipeQuantityLabel: String, assignedTo: String): Boolean {
        inTarget(assignedTo) {
            shoppingListDao.addAiChecklistItems(listOf(ChecklistShoppingMerge.Addition(itemName, category, recipeQuantityLabel)), assignedTo)
        }
        return true
    }

    suspend fun addAiChecklistItems(items: List<RecipeRepository.MissingChecklistItem>, assignedTo: String) = inTarget(assignedTo) {
        shoppingListDao.addAiChecklistItems(items.map { ChecklistShoppingMerge.Addition(it.item, it.category, it.neededQuantity) }, assignedTo)
    }

    suspend fun setChecked(item: ShoppingListItem, checked: Boolean) = shoppingListDao.setChecked(item.id, checked)

    suspend fun assignTo(item: ShoppingListItem, assignee: String) = inTarget(assignee) { shoppingListDao.assignTo(item.id, assignee) }

    suspend fun delete(item: ShoppingListItem) { shoppingListDao.delete(item) }

    suspend fun deleteChecked() = shoppingListDao.deleteChecked()
}

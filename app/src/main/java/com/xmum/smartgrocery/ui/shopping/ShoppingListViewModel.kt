package com.xmum.smartgrocery.ui.shopping

import androidx.room.withTransaction
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.data.repository.AppPreferencesRepository
import com.xmum.smartgrocery.data.repository.BudgetRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.xmum.smartgrocery.utils.DateUtils

enum class ShoppingMemberFilter(val label: String) {
    PERSONAL("Personal"),
    HOUSEHOLD("Household")
}

data class ShoppingBudgetImpact(
    val cartTotal: Double = 0.0,
    val totalBudget: Double = 0.0,
    val spentAmount: Double = 0.0,
    val remainingBudget: Double = 0.0
) {
    val exceedsBudget: Boolean
        get() = cartTotal > remainingBudget
}

class ShoppingListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartGroceryApp
    private val groceryRepository = app.groceryRepository
    private val shoppingListRepository = app.shoppingListRepository
    private val budgetRepository: BudgetRepository = app.budgetRepository
    private val preferencesRepository = app.preferencesRepository
    private val currentMonthYear = DateUtils.getCurrentMonthYear()
    private val startOfMonth = DateUtils.getStartOfMonth()
    private val endOfMonth = DateUtils.getEndOfMonth()

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()
    val activeMemberFilter: StateFlow<ShoppingMemberFilter> = app.sessionManager.sessions
        .map { if (it?.householdId == null) ShoppingMemberFilter.PERSONAL else ShoppingMemberFilter.HOUSEHOLD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ShoppingMemberFilter.PERSONAL)

    val allShoppingListItems: StateFlow<List<ShoppingListItem>> = shoppingListRepository.shoppingListItems
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // The Room query already enforces the selected personal or household scope.
    val shoppingListItems: StateFlow<List<ShoppingListItem>> = allShoppingListItems

    val expiryWarningDays: StateFlow<Int> = preferencesRepository.expiryWarningDays

    val budgetImpact: StateFlow<ShoppingBudgetImpact> = combine(
        allShoppingListItems,
        budgetRepository.getBudgetForMonth(currentMonthYear),
        budgetRepository.getTotalSpentForMonth(startOfMonth, endOfMonth)
    ) { items, budgetEntry, spent ->
        val cartTotal = items
            .filter { !it.isChecked }
            .sumOf { it.quantityNeeded.coerceAtLeast(1) * it.estimatedUnitPrice.coerceAtLeast(0.0) }
        val totalBudget = budgetEntry?.limitAmount ?: 0.0
        val spentAmount = spent ?: 0.0
        ShoppingBudgetImpact(
            cartTotal = cartTotal,
            totalBudget = totalBudget,
            spentAmount = spentAmount,
            remainingBudget = totalBudget - spentAmount
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ShoppingBudgetImpact()
    )

    fun setItemChecked(item: ShoppingListItem, checked: Boolean) {
        viewModelScope.launch {
            if (checked) {
                val totalPrice = item.quantityNeeded.coerceAtLeast(1) * item.estimatedUnitPrice
                completePurchasedItem(item, totalPrice.toUnitPrice(item.purchasedInventoryQuantity()))
            } else {
                shoppingListRepository.setChecked(item, false)
            }
        }
    }

    fun completeItemWithPurchasePrice(item: ShoppingListItem, totalPricePaid: Double) {
        viewModelScope.launch {
            val purchasedQuantity = item.purchasedInventoryQuantity()
            val unitPrice = totalPricePaid.toUnitPrice(purchasedQuantity)
            completePurchasedItem(item, unitPrice)
        }
    }

    private suspend fun completePurchasedItem(item: ShoppingListItem, unitPrice: Double) = app.database.withTransaction {
        val current = app.database.shoppingListDao().getById(item.id) ?: return@withTransaction
        require(current.userId == item.userId && current.householdId == item.householdId) { "Shopping scope changed; reload first." }
        val purchasedQuantity = item.purchasedInventoryQuantity()
        val inventoryItem = item.sourceItemId
            ?.let { groceryRepository.getItemById(it) }

        if (inventoryItem != null) {
            val restockedQuantity = maxOf(
                inventoryItem.quantity + purchasedQuantity,
                inventoryItem.threshold + 1
            )
            groceryRepository.update(inventoryItem.copy(price = unitPrice.coerceAtLeast(0.0)))
            groceryRepository.updateQuantity(inventoryItem.id, restockedQuantity)
        } else {
            groceryRepository.insert(
                GroceryItem(
                    name = item.itemName,
                    quantity = purchasedQuantity,
                    price = unitPrice.coerceAtLeast(0.0),
                    category = item.category,
                    subcategory = item.subcategory,
                    expiryDate = com.xmum.smartgrocery.utils.ExpiryEstimator.estimateExpiryTimestamp(item.itemName, item.categorySelection),
                    threshold = com.xmum.smartgrocery.utils.GroceryCategories.defaultThreshold(item.category)
                )
            )
        }

        shoppingListRepository.delete(item)
    }

    suspend fun addManualItem(itemName: String, category: String, quantityNeeded: Int,
        estimatedUnitPrice: Double, assignedTo: String): String? = try {
        shoppingListRepository.addManualItem(itemName, category, quantityNeeded, estimatedUnitPrice, assignedTo)
        null
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) { error.message ?: "Unable to add this shopping item." }

    fun assignItem(item: ShoppingListItem, assignee: String) {
        viewModelScope.launch {
            try { shoppingListRepository.assignTo(item, assignee) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _messages.emit(error.message ?: "Unable to change sharing scope.") }
        }
    }

    fun setMemberFilter(filter: ShoppingMemberFilter) {
        viewModelScope.launch {
            runCatching { app.sessionManager.selectScope(filter == ShoppingMemberFilter.HOUSEHOLD) }
                .onFailure { _messages.emit(it.message ?: "Unable to switch scope.") }
        }
    }

    fun buildShareText(): String {
        val openItems = allShoppingListItems.value.filter { !it.isChecked }
        if (openItems.isEmpty()) {
            return "Smart Grocery shopping list is clear."
        }

        return buildString {
            appendLine("Smart Grocery Shopping List")
            openItems
                .groupBy { it.assignedTo.displayAssignee() }
                .toSortedMap()
                .forEach { (member, items) ->
                    appendLine()
                    appendLine("$member:")
                    items.forEach { item ->
                        val cost = item.quantityNeeded * item.estimatedUnitPrice
                        appendLine("- ${item.itemName} x${item.quantityNeeded} (${item.category}) ~ RM ${String.format("%.2f", cost)}")
                    }
                }
        }.trim()
    }

    fun deleteItem(item: ShoppingListItem) {
        viewModelScope.launch {
            shoppingListRepository.delete(item)
        }
    }

    fun clearCheckedItems() {
        viewModelScope.launch {
            shoppingListRepository.deleteChecked()
        }
    }

    fun increaseExpiryWarningDays() {
        preferencesRepository.setExpiryWarningDays(expiryWarningDays.value + 1)
    }

    fun decreaseExpiryWarningDays() {
        preferencesRepository.setExpiryWarningDays(expiryWarningDays.value - 1)
    }

    private fun String.displayAssignee(): String {
        return when {
            equals("Shared", ignoreCase = true) || equals("Housemate", ignoreCase = true) -> "Household"
            else -> this
        }
    }

    private fun Double.toUnitPrice(quantityNeeded: Int): Double {
        return this / quantityNeeded.coerceAtLeast(1)
    }

    private fun ShoppingListItem.purchasedInventoryQuantity(): Int {
        return if (isRetailPantryItem()) {
            1
        } else {
            quantityNeeded.coerceAtLeast(1)
        }
    }

    private fun ShoppingListItem.isRetailPantryItem(): Boolean {
        return com.xmum.smartgrocery.utils.GroceryCategories.isFood(category) && (categorySelection.isRetailPantryText() || itemName.isRetailPantryText())
    }

    private fun String.isRetailPantryText(): Boolean {
        val normalized = lowercase()
        return listOf(
            "pantry",
            "seasoning",
            "seasonings",
            "spice",
            "spices",
            "salt",
            "sugar",
            "pepper",
            "soy sauce",
            "sauce",
            "oil",
            "vinegar",
            "flour",
            "starch",
            "cornstarch",
            "powder"
        ).any { normalized.contains(it) }
    }

}

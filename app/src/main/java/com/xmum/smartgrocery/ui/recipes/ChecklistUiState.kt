package com.xmum.smartgrocery.ui.recipes

import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.repository.RecipeRepository

data class ChecklistUiState(
    val availableIngredients: List<GroceryItem> = emptyList(),
    val expiryWarningDays: Int = 3,
    val userPrompt: String = "",
    val searchHistory: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasGenerated: Boolean = false,
    val generationError: String? = null,
    val checklistResult: RecipeRepository.GroceryChecklistResult? = null,
    val selectableMissingItems: List<SelectableChecklistItem> = emptyList(),
    val selectedMissingCount: Int = 0,
    val activeChecklistStatus: String? = null,
    val savedChecklists: List<SavedChecklist> = emptyList(),
    val selectableInventoryItems: List<SelectableInventoryChecklistItem> = emptyList(),
    val isClearingHistory: Boolean = false,
    val isAddingToShopping: Boolean = false
) {
    val selectedShoppingItems: List<RecipeRepository.MissingChecklistItem> get() =
        selectableMissingItems.filter { it.isSelected }.map { it.checklistItem } +
            selectableInventoryItems.filter { it.isSelected }.map { it.inventoryItem.asShoppingItem(availableIngredients) }
    val selectedShoppingCount: Int get() = selectedShoppingItems.size
    val hasSelectedItemsInInventory: Boolean get() = selectedShoppingItems.any { selected ->
        availableIngredients.any { stock -> stock.quantity > 0 && sameChecklistFood(stock.name, selected.item) }
    }
}

data class SelectableChecklistItem(
    val checklistItem: RecipeRepository.MissingChecklistItem,
    val isSelected: Boolean = true,
    val isAddedToShopping: Boolean = false,
    val isEditable: Boolean = true
)

data class SavedChecklist(
    val id: String,
    val dishName: String,
    val servings: Int,
    val status: String,
    val createdAt: Long,
    val missingChecklist: List<RecipeRepository.MissingChecklistItem>,
    val inventoryDeducted: List<RecipeRepository.DeductedInventoryItem>,
    val addedShoppingItemKeys: List<String>? = emptyList(),
    // The complete generation and user choices are stored together in Room's stateJson.
    val ingredients: List<RecipeRepository.ChecklistIngredient>? = emptyList(),
    val steps: List<String>? = emptyList(),
    val uncheckedInventoryItemKeys: List<String>? = emptyList(),
    val uncheckedMissingItemKeys: List<String>? = emptyList(),
    val userPrompt: String? = null
) {
    fun withCookingDetailsFrom(cached: SavedChecklist?): SavedChecklist =
        ChecklistHistorySnapshot.withLegacyDetails(this, cached)
}

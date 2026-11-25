package com.xmum.smartgrocery.ui.recipes

import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.repository.RecipeRepository

data class SelectableInventoryChecklistItem(
    val inventoryItem: RecipeRepository.DeductedInventoryItem,
    val isSelected: Boolean = true,
    val isAddedToShopping: Boolean = false
)

internal fun RecipeRepository.DeductedInventoryItem.inventorySelectionKey(): String =
    ChecklistSelection.inventoryKey(this)

internal fun RecipeRepository.DeductedInventoryItem.asShoppingItem(inventory: List<GroceryItem>): RecipeRepository.MissingChecklistItem =
    ChecklistSelection.asShoppingItem(this, inventory)

internal fun sameChecklistFood(first: String, second: String): Boolean =
    ChecklistSelection.sameFood(first, second)

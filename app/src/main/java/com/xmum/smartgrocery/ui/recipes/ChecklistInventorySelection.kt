package com.xmum.smartgrocery.ui.recipes

import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.repository.RecipeRepository
import com.xmum.smartgrocery.utils.GroceryCategories
import com.xmum.smartgrocery.utils.ShoppingVoiceMerge
import java.util.Locale

data class SelectableInventoryChecklistItem(
    val inventoryItem: RecipeRepository.DeductedInventoryItem,
    val isSelected: Boolean = true,
    val isAddedToShopping: Boolean = false
)

internal fun RecipeRepository.DeductedInventoryItem.inventorySelectionKey(): String =
    "inventory|${item.trim().lowercase(Locale.ROOT)}|${usedQuantity.trim().lowercase(Locale.ROOT)}"

internal fun RecipeRepository.DeductedInventoryItem.asShoppingItem(inventory: List<GroceryItem>): RecipeRepository.MissingChecklistItem {
    val stock = inventory.firstOrNull { sameChecklistFood(it.name, item) }
    val quantity = usedQuantity.replace(Regex("""\s*\(Fully Covered\)""", RegexOption.IGNORE_CASE), "").trim()
    return RecipeRepository.MissingChecklistItem(item, quantity,
        stock?.categorySelection ?: GroceryCategories.suggest(item))
}

internal fun sameChecklistFood(first: String, second: String): Boolean =
    ShoppingVoiceMerge.normalizedName(first) == ShoppingVoiceMerge.normalizedName(second)

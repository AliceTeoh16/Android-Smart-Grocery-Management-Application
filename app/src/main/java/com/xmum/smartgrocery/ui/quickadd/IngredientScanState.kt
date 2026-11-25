package com.xmum.smartgrocery.ui.quickadd

import com.xmum.smartgrocery.utils.GroceryCategories

data class BatchIngredientDraft(
    val id: String, val name: String, val category: String, val quantity: Int,
    val expiryDate: Long?, val selected: Boolean = true, val quantityDetected: Boolean = false,
    val expiryManuallySelected: Boolean = false, val manuallyAdded: Boolean = false,
    val threshold: Int = GroceryCategories.defaultThreshold(category),
)
data class IngredientScanState(
    val scanning: Boolean = false, val reviewing: Boolean = false,
    val items: List<BatchIngredientDraft> = emptyList(), val saving: Boolean = false,
    val savedCount: Int = 0, val error: String? = null,
) {
    val selectedCount: Int get() = items.count { it.selected }
    val canSave: Boolean get() = IngredientScanViewModel.canSave(this)
}

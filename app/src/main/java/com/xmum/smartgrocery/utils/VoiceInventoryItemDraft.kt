package com.xmum.smartgrocery.utils

import com.xmum.smartgrocery.data.local.entity.GroceryItem

/** Unsaved details for a new inventory item in a voice review. */
data class VoiceInventoryItemDraft(
    val commandIndex: Int,
    val name: String,
    val quantity: String,
    val price: String = "0.00",
    val category: String = "Dairy",
    val expiryDate: Long?,
    val threshold: String = "2",
) {
    val validationError: String? get() = when {
        name.isBlank() -> "Enter an item name."
        (quantity.toIntOrNull() ?: 0) <= 0 -> "Quantity must be a whole number greater than zero."
        price.toDoubleOrNull()?.let { it.isFinite() && it >= 0.0 } != true -> "Enter a valid price of zero or more."
        category !in Categories -> "Choose a category."
        expiryDate != null && expiryDate <= 0L -> "Choose a valid expiry date or leave it Not Set."
        threshold.toIntOrNull()?.let { it >= 0 } != true -> "Low-stock threshold must be a whole number of zero or more."
        else -> null
    }

    fun toGroceryItem(): GroceryItem {
        require(validationError == null) { validationError.orEmpty() }
        return GroceryItem(name = name.trim(), quantity = quantity.toInt(), price = price.toDouble(),
            category = category, expiryDate = expiryDate, threshold = threshold.toInt())
    }

    companion object {
        val Categories = GroceryCategories.all
        fun fromCommand(index: Int, command: VoiceCommand): VoiceInventoryItemDraft {
            val category = GroceryCategories.suggest(command.itemName)
            return VoiceInventoryItemDraft(
                commandIndex = index, name = command.itemName, quantity = command.quantity.toString(),
                category = category, expiryDate = ExpiryEstimator.estimateExpiryTimestamp(command.itemName, category),
                threshold = GroceryCategories.defaultThreshold(category).toString(),
            )
        }
    }
}

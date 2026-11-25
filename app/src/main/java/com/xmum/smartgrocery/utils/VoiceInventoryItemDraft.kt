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
    val validationError: String? get() = VoiceInventoryDraftRules.validationError(this)

    fun toGroceryItem(): GroceryItem = VoiceInventoryDraftRules.toGroceryItem(this)

    companion object {
        val Categories = GroceryCategories.all
        fun fromCommand(index: Int, command: VoiceCommand): VoiceInventoryItemDraft =
            VoiceInventoryDraftRules.fromCommand(index, command)
    }
}

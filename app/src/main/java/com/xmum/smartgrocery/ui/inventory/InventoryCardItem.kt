package com.xmum.smartgrocery.ui.inventory

import com.xmum.smartgrocery.data.local.entity.GroceryItem

data class InventoryBatch(
    val batchId: Int,
    val quantity: Int,
    val expiryDate: Long?,
    val price: Double
)

data class InventoryCardItem(
    val item: GroceryItem,
    val totalQuantity: Int,
    val earliestExpiryDate: Long?,
    val batches: List<InventoryBatch>,
    val changeIndicator: String? = item.sharedChangeIndicator
)

package com.xmum.smartgrocery.ui.history

data class PurchaseHistoryRecord(val id: Int, val name: String, val category: String, val quantity: Int,
    val unitPrice: Double, val timestamp: Long, val unit: String = "pcs") {
    val total: Double get() = quantity * unitPrice
}

data class PurchaseHistoryState(
    val records: List<PurchaseHistoryRecord> = emptyList(),
    val household: Boolean = false,
    val contextName: String = "Personal (private)",
    val search: String = "",
    val thisMonth: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val total: Double get() = records.sumOf { it.total }
}

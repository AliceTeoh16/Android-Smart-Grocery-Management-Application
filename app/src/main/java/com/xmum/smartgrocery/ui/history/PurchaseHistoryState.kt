package com.xmum.smartgrocery.ui.history

import com.xmum.smartgrocery.data.local.entity.InventoryChangeType
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import java.util.Calendar

data class PurchaseHistoryRecord(val id: Int, val name: String, val category: String, val quantity: Int,
    val unitPrice: Double, val timestamp: Long) {
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

internal object PurchaseHistoryMapper {
    fun records(logs: List<InventoryLog>, search: String, thisMonth: Boolean, now: Long): List<PurchaseHistoryRecord> {
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        val term = search.trim()
        return logs.asSequence()
            .filter { it.changeType == InventoryChangeType.ADD && it.amountChanged > 0 }
            .filter { !thisMonth || it.timestamp >= start.timeInMillis && it.timestamp < end }
            .filter { term.isEmpty() || it.itemName.contains(term, ignoreCase = true) || it.category.contains(term, ignoreCase = true) }
            .sortedWith(compareByDescending<InventoryLog> { it.timestamp }.thenByDescending { it.id })
            .map { PurchaseHistoryRecord(it.id, it.itemName, it.category, it.amountChanged, it.unitPriceAtChange, it.timestamp) }
            .toList()
    }
}

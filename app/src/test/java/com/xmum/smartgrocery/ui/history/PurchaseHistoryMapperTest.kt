package com.xmum.smartgrocery.ui.history

import com.xmum.smartgrocery.data.local.entity.InventoryChangeType
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class PurchaseHistoryMapperTest {
    private fun date(month: Int, day: Int) = Calendar.getInstance().apply {
        set(2026, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val now = date(Calendar.NOVEMBER, 9)
    private fun log(id: Int, name: String = "Milk", at: Long = now, type: String = InventoryChangeType.ADD,
        quantity: Int = 2, price: Double = 3.5) = InventoryLog(id, null, name, "Food & Beverage", type, quantity, price, at)

    @Test fun historicalSnapshotsAreNewestFirstAndExcludeUsageAndZeroAdditions() {
        val records = PurchaseHistoryMapper.records(listOf(log(1, at = now - 1000), log(2), log(3),
            log(4, type = InventoryChangeType.DEDUCT), log(5, quantity = 0)), "", false, now)
        assertEquals(listOf(3, 2, 1), records.map { it.id })
        assertEquals(21.0, PurchaseHistoryState(records = records).total, 0.0)
    }

    @Test fun monthlyRangeIncludesItsFirstInstantAndExcludesTheNextMonth() {
        val start = date(Calendar.NOVEMBER, 1)
        val end = date(Calendar.DECEMBER, 1)
        val records = PurchaseHistoryMapper.records(listOf(log(1, at = start - 1), log(2, at = start),
            log(3, at = end - 1), log(4, at = end)), "", true, now)
        assertEquals(listOf(3, 2), records.map { it.id })
    }

    @Test fun searchMatchesNamesOrCategoriesAndTotalsUseHistoricalPrices() {
        val logs = listOf(log(1, "Milk", quantity = 2, price = 3.5),
            log(2, "Soap", quantity = 3, price = 2.0).copy(category = "Personal Care"))
        assertEquals(listOf(1), PurchaseHistoryMapper.records(logs, " MILK ", false, now).map { it.id })
        val soap = PurchaseHistoryMapper.records(logs, "personal", false, now)
        assertEquals(listOf(2), soap.map { it.id })
        assertEquals(6.0, PurchaseHistoryState(records = soap).total, 0.0)
        assertTrue(PurchaseHistoryMapper.records(logs, "not purchased", false, now).isEmpty())
    }
}

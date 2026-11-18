package com.xmum.smartgrocery.ui.pantrypal

import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.ui.auth.LocalAuthProfile
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class HomeOverviewMapperTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 16, 10, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val day = 86_400_000L
    private val profile = LocalAuthProfile(true, "aisha@example.com", "Aisha Lim")
    private fun item(id: Int, name: String, quantity: Int, expires: Long, threshold: Int = 2) = GroceryItem(
        id = id, name = name, quantity = quantity, price = 4.0, category = "Dairy", expiryDate = expires, threshold = threshold,
    )
    private fun snapshot(items: List<GroceryItem> = emptyList()) = DashboardSnapshot(items, emptyList(), emptyList(), null, 0.0)

    @Test fun householdLabelFollowsMemberCountUpdatesInsteadOfRetainingTheInitialZero() {
        val initial = HomeOverviewMapper.map(snapshot(), profile, 3, now)
        assertEquals("Shared with 0 members", initial.householdSubtitle)

        val household = initial.copy(householdName = "Testing", memberCount = 1)
        assertEquals("Shared with 1 member", household.householdSubtitle)

        val joined = household.copy(memberCount = 3)
        assertEquals("Shared with 3 members", joined.householdSubtitle)
        assertEquals("Testing", joined.householdName)

        val left = joined.copy(memberCount = 1)
        assertEquals("Shared with 1 member", left.householdSubtitle)
        assertEquals("Shared with 0 members", left.copy(memberCount = 0).householdSubtitle)
    }

    @Test fun expiryUsesConfiguredWindowAndDistinctInStockNames() {
        val data = snapshot(listOf(
            item(1, "Milk", 1, now + day), item(2, "milk", 2, now + day * 2),
            item(3, "Eggs", 0, now + day), item(4, "Expired", 1, now - day),
            item(5, "Beef", 1, now + day * 4),
        ))
        val threeDayState = HomeOverviewMapper.map(data, profile, 3, now)
        assertEquals(1, threeDayState.expiringCount)
        assertTrue(threeDayState.expiringSummary.contains("Milk"))
        assertEquals(2, HomeOverviewMapper.map(data, profile, 5, now).expiringCount)
    }

    @Test fun lowStockUsesExistingBatchThresholdsIncludingEmptyItems() {
        val data = snapshot(listOf(
            item(1, "Milk", 2, now + day), item(2, "Milk", 1, now + day),
            item(3, "Eggs", 0, now + day), item(4, "Rice", 10, now + day),
        ))
        val state = HomeOverviewMapper.map(data, profile, 3, now)
        assertEquals(2, state.lowStockCount)
        assertEquals("Milk & Eggs", state.lowStockSummary)
    }

    @Test fun budgetUsesRecordedSpendingAndHandlesUnsetAndExceededLimits() {
        val data = snapshot().copy(budget = BudgetEntry("2026-10", 500.0), spent = 242.3)
        assertEquals(String.format(Locale.getDefault(), "RM%.2f", 257.7), HomeOverviewMapper.map(data, profile, 3, now).budgetRemainingLabel)
        assertEquals(String.format(Locale.getDefault(), "RM%.2f", 0.0), HomeOverviewMapper.map(data.copy(spent = 600.0), profile, 3, now).budgetRemainingLabel)
        assertEquals("Set budget", HomeOverviewMapper.map(data.copy(budget = null), profile, 3, now).budgetRemainingLabel)
    }

    @Test fun activityMergesRealLogsAndShoppingUpdatesNewestFirst() {
        val data = snapshot().copy(
            logs = listOf(InventoryLog(1, 1, "Milk", "Dairy", InventoryChangeType.ADD, 2, 4.0, now - 120_000)),
            shopping = listOf(ShoppingListItem(
                id = 1, itemName = "Eggs", category = "Dairy", quantityNeeded = 1, estimatedUnitPrice = 5.0,
                isChecked = true, updatedAt = now - 60_000,
            )),
        )
        val state = HomeOverviewMapper.map(data, profile, 3, now)
        assertEquals("Aisha", state.displayName)
        assertEquals("AL", state.initials)
        assertEquals("Picked up Eggs", state.activity[0].title)
        assertEquals("Done", state.activity[0].result)
        assertEquals("Added Milk", state.activity[1].title)
        assertEquals("+2", state.activity[1].result)
    }
}

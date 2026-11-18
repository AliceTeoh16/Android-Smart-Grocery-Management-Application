package com.xmum.smartgrocery.ui.pantrypal

import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.BudgetEntry
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.InventoryChangeType
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.ui.auth.LocalAuthProfile
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Read-only UI adapter over existing repository APIs. No ViewModel or Room changes are needed. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HomeOverviewDataSource(app: SmartGroceryApp, session: LocalAuthSession) {
    private val clock = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }
    private val month = clock.map { DateUtils.getCurrentMonthYear() }.distinctUntilChanged()
    private val inventoryLogs = app.groceryRepository.allGroceryItems
        .map { items -> items.map { it.id }.sorted() }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            // The existing repository exposes logs by item, so compose those read-only flows.
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map(app.groceryRepository::getLogsForItem)) { logs -> logs.flatMap { it } }
        }
    private val snapshot = combine(
        app.groceryRepository.allGroceryItems,
        inventoryLogs,
        app.shoppingListRepository.shoppingListItems,
        month.flatMapLatest(app.budgetRepository::getBudgetForMonth),
        month.flatMapLatest {
            app.budgetRepository.getTotalSpentForMonth(DateUtils.getStartOfMonth(), DateUtils.getEndOfMonth())
        },
    ) { items, logs, shopping, budget, spent -> DashboardSnapshot(items, logs, shopping, budget, spent ?: 0.0) }

    val state: Flow<HomeOverviewUiState> = combine(
        snapshot, app.preferencesRepository.expiryWarningDays, session.profiles, clock,
    ) { data, warningDays, profile, now -> HomeOverviewMapper.map(data, profile, warningDays, now) }
        .combine(session.manager.context) { state, context -> state.copy(householdName = context?.name ?: "Personal (private)", memberCount = context?.memberCount ?: 0) }
}

internal data class DashboardSnapshot(
    val inventory: List<GroceryItem>,
    val logs: List<InventoryLog>,
    val shopping: List<ShoppingListItem>,
    val budget: BudgetEntry?,
    val spent: Double,
)

/** Pure presentation mapping, with an explicit clock for deterministic boundary tests. */
internal object HomeOverviewMapper {
    fun map(data: DashboardSnapshot, profile: LocalAuthProfile, warningDays: Int, now: Long): HomeOverviewUiState {
        val locale = Locale.getDefault()
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Match DateUtils.isExpiringWithin: today's entire day plus the configured warning window.
        val endOfToday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis - 1
        val expiryEnd = endOfToday + warningDays.coerceAtLeast(1) * 86_400_000L
        val expiring = data.inventory.filter { it.quantity > 0 && it.expiryDate?.let { date -> date in today..expiryEnd } == true }
            .sortedBy { it.expiryDate }.distinctBy { it.name.trim().lowercase(locale) }
        // Same per-batch threshold rule used by ShoppingListRepository's existing low-stock sync.
        val lowStock = data.inventory.filter { it.isLowStock }.distinctBy { it.name.trim().lowercase(locale) }
        val name = profile.displayName.ifBlank { profile.email.substringBefore('@') }.ifBlank { "Pantry pal" }
        val nameParts = name.split(' ', '.', '_', '-').filter { it.isNotBlank() }
        val initials = nameParts.take(2).joinToString("") { it.first().uppercaseChar().toString() }
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val activity = buildList {
            data.logs.forEach { log ->
                val added = log.changeType == InventoryChangeType.ADD
                add(TimedActivity(log.timestamp, PantryActivity(
                    id = "inventory-${log.id}", title = "${if (added) "Added" else "Used"} ${log.itemName}",
                    detail = "${log.category} · ${relativeTime(log.timestamp, now)}",
                    result = "${if (added) "+" else "−"}${log.amountChanged}", kind = ActivityKind.Inventory,
                )))
            }
            data.shopping.forEach { item ->
                val timestamp = if (item.isChecked) item.updatedAt else item.createdAt
                add(TimedActivity(timestamp, PantryActivity(
                    id = "shopping-${item.id}",
                    title = if (item.isChecked) "Picked up ${item.itemName}" else "Listed ${item.itemName}",
                    detail = "Shopping list · ${relativeTime(timestamp, now)}",
                    result = if (item.isChecked) "Done" else "To buy",
                    kind = if (item.isChecked) ActivityKind.Completed else ActivityKind.Shopping,
                )))
            }
        }.sortedWith(compareByDescending<TimedActivity> { it.timestamp }.thenBy { it.activity.id })
            .take(50).map { it.activity }

        return HomeOverviewUiState(
            displayName = nameParts.firstOrNull() ?: name, initials = initials.ifBlank { "PP" },
            dateLabel = SimpleDateFormat("EEEE, d MMMM", locale).format(Date(now)).uppercase(locale),
            householdName = "Personal (private)", memberCount = 0,
            expiringCount = expiring.size,
            expiringSummary = if (expiring.isEmpty()) "No items expiring in the next $warningDays days."
                else "Use ${summarize(expiring.map { it.name })} in the next $warningDays days.",
            lowStockCount = lowStock.size,
            lowStockSummary = if (lowStock.isEmpty()) "All stocked up" else summarize(lowStock.map { it.name }),
            budgetRemainingLabel = data.budget?.let { String.format(locale, "RM%.2f", (it.limitAmount - data.spent).coerceAtLeast(0.0)) }
                ?: "Set budget",
            budgetSubtitle = if (data.budget == null) "Plan this month · View →" else "Left this month · View →",
            activity = activity,
            greeting = when (hour) { in 0..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" },
        )
    }

    private data class TimedActivity(val timestamp: Long, val activity: PantryActivity)

    private fun summarize(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.first()
        2 -> names.joinToString(" & ")
        else -> "${names.take(2).joinToString(" & ")} +${names.size - 2} more"
    }

    private fun relativeTime(timestamp: Long, now: Long): String {
        val minutes = ((now - timestamp).coerceAtLeast(0) / 60_000)
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 1_440 -> "${minutes / 60} ${if (minutes / 60 == 1L) "hour" else "hours"} ago"
            else -> "${minutes / 1_440} ${if (minutes / 1_440 == 1L) "day" else "days"} ago"
        }
    }
}

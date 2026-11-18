package com.xmum.smartgrocery.ui.budget

import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.dao.CategorySpend
import com.xmum.smartgrocery.data.local.entity.BudgetEntry
import com.xmum.smartgrocery.ui.common.NotificationHelper
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BudgetUiState(
    val limit: Double = 0.0,
    val periodSpent: Double = 0.0,
    val monthlySpent: Double = 0.0,
    val remaining: Double = 0.0,
    val progressPercent: Int = 0,
    val spentLabel: String = "Spent this month"
)

enum class BudgetScope { PERSONAL, HOUSEHOLD }

enum class BudgetRange(val spentLabel: String) {
    WEEK("Spent this week"),
    MONTH("Spent this month"),
    LAST_30_DAYS("Spent last 30 days")
}

private data class BudgetDateRange(
    val start: Long,
    val end: Long
)

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartGroceryApp
    private val repository = app.budgetRepository
    private val notificationHelper = NotificationHelper(application)

    private val currentMonthYear = DateUtils.getCurrentMonthYear()
    private val startOfMonth = DateUtils.getStartOfMonth()
    private val endOfToday = DateUtils.getEndOfToday()
    private val selectedRange = MutableStateFlow(BudgetRange.MONTH)
    private val session = app.sessionManager.sessions.stateIn(viewModelScope, SharingStarted.Eagerly, LocalSessionEntity())
    val activeScope = session.map { if (it?.householdId == null) BudgetScope.PERSONAL else BudgetScope.HOUSEHOLD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BudgetScope.PERSONAL)
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    fun setScope(scope: BudgetScope) {
        viewModelScope.launch {
            runCatching { app.sessionManager.selectScope(scope == BudgetScope.HOUSEHOLD) }
                .onFailure { _messages.emit(it.message ?: "Unable to switch budget scope.") }
        }
    }

    // Flag to avoid spamming notification on every state update
    private var limitAlertTriggered = false

    val activeRange: StateFlow<BudgetRange> = selectedRange
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BudgetRange.MONTH
        )

    private val dateRange: StateFlow<BudgetDateRange> = selectedRange
        .map { range ->
            when (range) {
                BudgetRange.WEEK -> BudgetDateRange(DateUtils.getStartOfWeek(), DateUtils.getEndOfToday())
                BudgetRange.MONTH -> BudgetDateRange(DateUtils.getStartOfMonth(), DateUtils.getEndOfToday())
                BudgetRange.LAST_30_DAYS -> BudgetDateRange(DateUtils.getStartOfLast30Days(), DateUtils.getEndOfToday())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BudgetDateRange(startOfMonth, endOfToday)
        )

    // Fetch the budget entry configuration for current month
    val budgetEntry: StateFlow<BudgetEntry?> = repository.getBudgetForMonth(currentMonthYear)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Budget progress is always based on the current month because the limit is monthly.
    val monthlySpent: StateFlow<Double> = repository.getTotalSpentForMonth(startOfMonth, DateUtils.getEndOfMonth())
        .map { it ?: 0.0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0.0
        )

    // This total follows the selected graph period.
    val periodSpent: StateFlow<Double> = dateRange
        .flatMapLatest { range ->
            repository.getTotalSpentForMonth(range.start, range.end)
        }
        .map { it ?: 0.0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0.0
        )

    // Fetch breakdown of spending grouped by grocery category for the selected period.
    val categorySpending: StateFlow<List<CategorySpend>> = dateRange
        .flatMapLatest { range ->
            repository.getCategorySpending(range.start, range.end)
        }
        .map { spending -> spending.sortedByDescending { it.categoryTotal } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Combined UI state
    val uiState: StateFlow<BudgetUiState> = combine(
        budgetEntry,
        periodSpent,
        monthlySpent,
        activeRange
    ) { entry, spentInPeriod, spentThisMonth, range ->
        val limit = entry?.limitAmount ?: 0.0
        val remaining = (limit - spentThisMonth).coerceAtLeast(0.0)
        val progress = if (limit > 0) {
            ((spentThisMonth / limit) * 100).toInt().coerceAtMost(100)
        } else {
            0
        }

        // Budget alerting mechanism
        if (limit > 0 && spentThisMonth > limit && !limitAlertTriggered) {
            limitAlertTriggered = true
            notificationHelper.sendOverBudgetNotification(spentThisMonth, limit)
        } else if (spentThisMonth <= limit) {
            limitAlertTriggered = false
        }

        BudgetUiState(
            limit = limit,
            periodSpent = spentInPeriod,
            monthlySpent = spentThisMonth,
            remaining = remaining,
            progressPercent = progress,
            spentLabel = range.spentLabel
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BudgetUiState()
    )

    fun updateBudgetLimit(limit: Double) {
        val selected = session.value
        val userId = selected?.userId
        val expected = userId?.let { DataScope(it, selected.householdId) }
        viewModelScope.launch {
            if (expected == null) { _messages.emit("Sign in before setting a budget."); return@launch }
            runCatching {
                repository.setBudget(BudgetEntry(monthYear = currentMonthYear, limitAmount = limit, scopeKey = expected.key), expected)
            }.fold(
                onSuccess = { _messages.emit("${if (expected.householdId == null) "Personal" else "Household"} budget limit updated.") },
                onFailure = { _messages.emit(it.message ?: "Unable to save budget limit.") })
        }
    }

    fun setRange(range: BudgetRange) {
        selectedRange.value = range
    }
}

package com.xmum.smartgrocery.ui.budget;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelKt;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.dao.CategorySpend;
import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.entity.BudgetEntry;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.repository.BudgetRepository;
import com.xmum.smartgrocery.ui.common.NotificationHelper;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.DateUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

public final class BudgetViewModel extends AndroidViewModel {
    private static final SharingStarted WHILE_SUBSCRIBED =
            SharingStarted.Companion.WhileSubscribed(5000L, Long.MAX_VALUE);

    private final SmartGroceryApp app;
    private final BudgetRepository repository;
    private final NotificationHelper notificationHelper;
    private final CoroutineScope scope;
    private final String currentMonthYear;
    private final MutableStateFlow<BudgetRange> selectedRange = StateFlowKt.MutableStateFlow(BudgetRange.MONTH);
    private final MutableSharedFlow<String> outgoingMessages = SharedFlowKt.MutableSharedFlow(0, 0, BufferOverflow.SUSPEND);
    private final SharedFlow<String> messages = FlowKt.asSharedFlow(outgoingMessages);

    private final StateFlow<LocalSessionEntity> session;
    private final StateFlow<BudgetScope> activeScope;
    private final StateFlow<BudgetRange> activeRange;
    private final StateFlow<BudgetEntry> budgetEntry;
    private final StateFlow<Double> monthlySpent;
    private final StateFlow<Double> periodSpent;
    private final StateFlow<List<CategorySpend>> categorySpending;
    private final StateFlow<BudgetUiState> uiState;

    private boolean limitAlertTriggered;

    public BudgetViewModel(@NonNull Application application) {
        super(application);
        app = (SmartGroceryApp) application;
        repository = app.getBudgetRepository();
        notificationHelper = new NotificationHelper(application);
        scope = ViewModelKt.getViewModelScope(this);

        currentMonthYear = DateUtils.getCurrentMonthYear();
        long startOfMonth = DateUtils.getStartOfMonth();
        long endOfToday = DateUtils.getEndOfToday();

        SharingStarted eagerly = SharingStarted.Companion.getEagerly();
        session = FlowKt.stateIn(app.getSessionManager().getSessions(), scope, eagerly, new LocalSessionEntity());
        activeScope = FlowKt.stateIn(FlowKt.map(session, (value, ignored) ->
                value == null || value.getHouseholdId() == null ? BudgetScope.PERSONAL : BudgetScope.HOUSEHOLD),
                scope, eagerly, BudgetScope.PERSONAL);
        activeRange = stateIn(selectedRange, BudgetRange.MONTH);
        StateFlow<BudgetDateRange> dateRange = stateIn(FlowKt.map(selectedRange, (range, ignored) -> dateRange(range)),
                new BudgetDateRange(startOfMonth, endOfToday));

        budgetEntry = stateIn(repository.getBudgetForMonth(currentMonthYear), null);
        monthlySpent = stateIn(orZero(repository.getTotalSpentForMonth(startOfMonth, DateUtils.getEndOfMonth())), 0.0);
        periodSpent = stateIn(orZero(FlowKt.flatMapLatest(dateRange, (range, ignored) ->
                repository.getTotalSpentForMonth(range.start, range.end))), 0.0);
        Flow<List<CategorySpend>> categorySpendingForRange = FlowKt.flatMapLatest(dateRange, (range, ignored) ->
                repository.getCategorySpending(range.start, range.end));
        categorySpending = stateIn(FlowKt.map(categorySpendingForRange, (spending, ignored) -> {
                    List<CategorySpend> ordered = new ArrayList<>(spending);
                    ordered.sort(Comparator.comparingDouble(CategorySpend::getCategoryTotal).reversed());
                    return ordered;
                }), Collections.emptyList());
        uiState = stateIn(FlowKt.combine(budgetEntry, periodSpent, monthlySpent, activeRange,
                (entry, spending, monthly, range, ignored) -> summarize(entry, spending, monthly, range)),
                new BudgetUiState());
    }

    @NonNull
    public StateFlow<BudgetScope> getActiveScope() { return activeScope; }

    @NonNull
    public StateFlow<BudgetRange> getActiveRange() { return activeRange; }

    @NonNull
    public SharedFlow<String> getMessages() { return messages; }

    @NonNull
    public StateFlow<BudgetEntry> getBudgetEntry() { return budgetEntry; }

    @NonNull
    public StateFlow<Double> getMonthlySpent() { return monthlySpent; }

    @NonNull
    public StateFlow<Double> getPeriodSpent() { return periodSpent; }

    @NonNull
    public StateFlow<List<CategorySpend>> getCategorySpending() { return categorySpending; }

    @NonNull
    public StateFlow<BudgetUiState> getUiState() { return uiState; }

    public void setScope(@NonNull BudgetScope budgetScope) {
        AndroidCoroutines.<Unit>launchWithMessage(scope, outgoingMessages,
                continuation -> app.getSessionManager().selectScope(budgetScope == BudgetScope.HOUSEHOLD, continuation),
                unused -> null, "Unable to switch budget scope.");
    }

    public void updateBudgetLimit(double limit) {
        LocalSessionEntity selected = session.getValue();
        if (selected == null || selected.getUserId() == null) {
            AndroidCoroutines.emit(scope, outgoingMessages, "Sign in before setting a budget.");
            return;
        }
        DataScope expected = new DataScope(selected.getUserId(), selected.getHouseholdId());
        BudgetEntry draft = new BudgetEntry(currentMonthYear, limit, "", null, expected.getKey());
        AndroidCoroutines.<Long>launchWithMessage(scope, outgoingMessages,
                continuation -> repository.setBudget(draft, expected, continuation),
                unused -> (expected.getHouseholdId() == null ? "Personal" : "Household") + " budget limit updated.",
                "Unable to save budget limit.");
    }

    public void setRange(@NonNull BudgetRange range) {
        selectedRange.setValue(range);
    }

    private BudgetUiState summarize(BudgetEntry entry, double spending, double monthly, BudgetRange range) {
        double limit = entry != null ? entry.getLimitAmount() : 0.0;
        double remaining = Math.max(limit - monthly, 0.0);
        int progress = limit > 0 ? Math.min((int) ((monthly / limit) * 100), 100) : 0;
        if (limit > 0 && monthly > limit && !limitAlertTriggered) {
            limitAlertTriggered = true;
            notificationHelper.sendOverBudgetNotification(monthly, limit);
        } else if (monthly <= limit) {
            limitAlertTriggered = false;
        }
        return new BudgetUiState(limit, spending, monthly, remaining, progress, range.getSpentLabel());
    }

    private <T> StateFlow<T> stateIn(Flow<T> flow, T initial) {
        return FlowKt.stateIn(flow, scope, WHILE_SUBSCRIBED, initial);
    }

    private static Flow<Double> orZero(Flow<Double> spending) {
        return FlowKt.map(spending, (value, ignored) -> value == null ? 0.0 : value);
    }

    private static BudgetDateRange dateRange(BudgetRange range) {
        long start;
        switch (range) {
            case WEEK:
                start = DateUtils.getStartOfWeek();
                break;
            case LAST_30_DAYS:
                start = DateUtils.getStartOfLast30Days();
                break;
            default:
                start = DateUtils.getStartOfMonth();
                break;
        }
        return new BudgetDateRange(start, DateUtils.getEndOfToday());
    }

    private static final class BudgetDateRange {
        private final long start;
        private final long end;

        private BudgetDateRange(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}

package com.xmum.smartgrocery.ui.history;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelKt;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.dao.DataContextInfo;
import com.xmum.smartgrocery.data.local.entity.InventoryLog;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.utils.AndroidCoroutines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

public final class PurchaseHistoryViewModel extends AndroidViewModel {
    private static final String SEARCH_KEY = "purchase_search";
    private static final String MONTH_KEY = "purchase_this_month";
    private static final String PRIVATE_CONTEXT = "Personal (private)";

    private final SmartGroceryApp app;
    private final SavedStateHandle saved;
    private final CoroutineScope scope;
    private final MutableStateFlow<String> message = StateFlowKt.MutableStateFlow(null);
    private final StateFlow<PurchaseHistoryState> state;

    public PurchaseHistoryViewModel(@NonNull Application application, @NonNull SavedStateHandle saved) {
        super(application);
        app = (SmartGroceryApp) application;
        this.saved = saved;
        scope = ViewModelKt.getViewModelScope(this);

        StateFlow<String> search = saved.getStateFlow(SEARCH_KEY, "");
        StateFlow<Boolean> month = saved.getStateFlow(MONTH_KEY, false);
        Flow<LocalSessionEntity> sessions = FlowKt.onEach(app.getSessionManager().getSessions(), (session, ignored) -> {
            message.setValue(null);
            return Unit.INSTANCE;
        });
        Flow<HistorySource> history = FlowKt.flatMapLatest(sessions, (session, ignored) -> observeHistory(session));
        Flow<PurchaseHistoryState> states = FlowKt.combine(history, search, month, message,
                (data, term, monthly, error, ignored) -> new PurchaseHistoryState(
                        PurchaseHistoryMapper.records(data.logs, term, monthly, System.currentTimeMillis()),
                        data.household, data.contextName, term, monthly, data.loading, error != null ? error : data.error));
        state = FlowKt.stateIn(AndroidCoroutines.background(states), scope, SharingStarted.Companion.WhileSubscribed(5000L, Long.MAX_VALUE),
                new PurchaseHistoryState());
    }

    @NonNull
    public StateFlow<PurchaseHistoryState> getState() {
        return state;
    }

    public void search(@NonNull String value) {
        saved.set(SEARCH_KEY, value);
    }

    public void thisMonth(boolean value) {
        saved.set(MONTH_KEY, value);
    }

    public void selectHousehold(boolean household) {
        message.setValue(null);
        AndroidCoroutines.<Unit>launchWithError(scope,
                continuation -> app.getSessionManager().selectScope(household, continuation),
                error -> message.setValue(error.getMessage() != null ? error.getMessage()
                        : "Unable to change purchase history scope."));
    }

    private Flow<HistorySource> observeHistory(LocalSessionEntity session) {
        if (session == null || session.getUserId() == null) {
            return FlowKt.flowOf(new HistorySource(Collections.emptyList(), false, PRIVATE_CONTEXT, false,
                    "Sign in to view your purchase history."));
        }
        String user = session.getUserId();
        String household = session.getHouseholdId();
        String contextName = household == null ? PRIVATE_CONTEXT : "Household";
        Flow<HistorySource> observed = FlowKt.combine(app.getGroceryRepository().getPurchaseHistory(),
                app.getSessionManager().getContext(), (logs, context, ignored) ->
                        scopedHistory(logs, context, user, household, contextName));
        Flow<HistorySource> withLoading = FlowKt.onStart(observed, (collector, next) ->
                collector.emit(new HistorySource(Collections.emptyList(), household != null, contextName, true, null), next));
        return AndroidCoroutines.recoverFlow(withLoading, error ->
                new HistorySource(Collections.emptyList(), household != null, PRIVATE_CONTEXT, false,
                        "Couldn’t load purchase history. Reopen this screen to retry."));
    }

    private static HistorySource scopedHistory(List<InventoryLog> logs, DataContextInfo context, String user,
            String household, String contextName) {
        List<InventoryLog> scoped = new ArrayList<>();
        for (InventoryLog log : logs) {
            if (household == null ? log.getHouseholdId() == null && user.equals(log.getUserId())
                    : household.equals(log.getHouseholdId())) {
                scoped.add(log);
            }
        }
        return new HistorySource(scoped, household != null, context == null ? contextName : context.getName(), false, null);
    }

    private static final class HistorySource {
        private final List<InventoryLog> logs;
        private final boolean household;
        private final String contextName;
        private final boolean loading;
        private final String error;

        private HistorySource(List<InventoryLog> logs, boolean household, String contextName, boolean loading, String error) {
            this.logs = logs;
            this.household = household;
            this.contextName = contextName;
            this.loading = loading;
            this.error = error;
        }
    }
}

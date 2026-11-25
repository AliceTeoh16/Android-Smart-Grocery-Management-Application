package com.xmum.smartgrocery.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.dao.ChecklistHistoryStore;
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

public final class ChecklistHistoryRepository {
    private final ChecklistHistoryStore storage;
    private final Flow<List<ChecklistHistoryItem>> allHistory;

    public ChecklistHistoryRepository(@NonNull ChecklistHistoryStore storage) {
        this.storage = storage;
        this.allHistory = storage.getAllChecklistHistory();
    }

    @NonNull
    public Flow<List<ChecklistHistoryItem>> getAllHistory() {
        return allHistory;
    }

    @NonNull
    public Object getAllHistorySync(@NonNull Continuation<? super List<ChecklistHistoryItem>> continuation) {
        return storage.getAllChecklistHistorySync(continuation);
    }

    @Nullable
    public Object getById(@NonNull String id, @NonNull Continuation<? super ChecklistHistoryItem> continuation) {
        return storage.getChecklistHistoryById(id, continuation);
    }

    @NonNull
    public Object saveChecklistWithPruning(@NonNull ChecklistHistoryItem item,
            @NonNull Continuation<? super Unit> continuation) {
        return saveChecklistWithPruning(item, 10, continuation);
    }

    @NonNull
    public Object saveChecklistWithPruning(@NonNull ChecklistHistoryItem item, int maxLimit,
            @NonNull Continuation<? super Unit> continuation) {
        return storage.saveWithPruning(item, maxLimit, continuation);
    }

    @NonNull
    public Object pruneToLatest(@NonNull Continuation<? super Unit> continuation) {
        return storage.pruneToLatest(10, continuation);
    }

    @NonNull
    public Object backfillState(@NonNull ChecklistHistoryItem item,
            @NonNull Continuation<? super Unit> continuation) {
        return storage.backfillState(item.getId(), item.getStateJson(), continuation);
    }

    @NonNull
    public Object updateState(@NonNull ChecklistHistoryItem item, @Nullable String status,
            @NonNull Continuation<? super Unit> continuation) {
        return storage.updateState(item.getId(), item.getStateJson(), item.getAddedShoppingKeysJson(), status, continuation);
    }

    @NonNull
    public Object updateState(@NonNull ChecklistHistoryItem item, @NonNull Continuation<? super Unit> continuation) {
        return updateState(item, null, continuation);
    }

    @NonNull
    public Object updateStatus(@NonNull String id, @NonNull String status,
            @NonNull Continuation<? super Unit> continuation) {
        return storage.updateStatus(id, status, continuation);
    }

    @NonNull
    public Object updateAddedShoppingKeys(@NonNull String id, @NonNull String addedShoppingKeysJson,
            @NonNull Continuation<? super Unit> continuation) {
        return storage.updateAddedShoppingKeys(id, addedShoppingKeysJson, continuation);
    }

    @NonNull
    public Object deleteById(@NonNull String id, @NonNull Continuation<? super Unit> continuation) {
        return storage.deleteById(id, continuation);
    }

    @NonNull
    public Object clearAll(@NonNull Continuation<? super Unit> continuation) {
        return storage.clearAll(continuation);
    }
}

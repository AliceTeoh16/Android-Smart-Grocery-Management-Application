package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

public interface ChecklistHistoryStore {
    @NonNull
    Flow<List<ChecklistHistoryItem>> getAllChecklistHistory();

    @NonNull
    Object getAllChecklistHistorySync(@NonNull Continuation<? super List<ChecklistHistoryItem>> continuation);

    @Nullable
    Object getChecklistHistoryById(@NonNull String id,
            @NonNull Continuation<? super ChecklistHistoryItem> continuation);

    @NonNull
    Object saveWithPruning(@NonNull ChecklistHistoryItem item, int maxLimit,
            @NonNull Continuation<? super Unit> continuation);

    @NonNull
    Object pruneToLatest(int maxLimit,
            @NonNull Continuation<? super Unit> continuation);

    @NonNull
    Object updateState(@NonNull String id,
            @NonNull String stateJson,
            @NonNull String addedShoppingKeysJson,
            @Nullable String status,
            @NonNull Continuation<? super Unit> continuation);

    @NonNull
    Object backfillState(@NonNull String id, @NonNull String stateJson,
            @NonNull Continuation<? super Unit> continuation);

    @NonNull
    Object updateStatus(@NonNull String id, @NonNull String status,
            @NonNull Continuation<? super Unit> continuation);

    @NonNull
    Object updateAddedShoppingKeys(@NonNull String id, @NonNull String addedShoppingKeysJson,
            @NonNull Continuation<? super Unit> continuation);

    @NonNull
    Object deleteById(@NonNull String id,
            @NonNull Continuation<? super Unit> continuation);

    @NonNull
    Object clearAll(@NonNull Continuation<? super Unit> continuation);
}

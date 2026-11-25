package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;
import androidx.room.Update;

import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Dao
public abstract class ChecklistHistoryDao extends ScopedDao implements ChecklistHistoryStore {
    protected ChecklistHistoryDao(@NonNull RoomDatabase database) {
        super(database);
    }

    @Override
    @NonNull
    @Query("SELECT * FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at DESC, id DESC LIMIT 10")
    public abstract Flow<List<ChecklistHistoryItem>> getAllChecklistHistory();

    @NonNull
    @Query("SELECT * FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at DESC, id DESC LIMIT 10")
    protected abstract List<ChecklistHistoryItem> readRecent();

    @Nullable
    @Query("SELECT * FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id = :id LIMIT 1")
    protected abstract ChecklistHistoryItem readById(@NonNull String id);

    @Query("SELECT COUNT(*) FROM checklist_history WHERE userId = " + ScopeSql.USER)
    protected abstract int countRows();

    @Insert
    protected abstract void insertRaw(@NonNull ChecklistHistoryItem item);

    @Update
    protected abstract void updateRaw(@NonNull ChecklistHistoryItem item);

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id NOT IN (SELECT id FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at DESC, id DESC LIMIT :maxLimit)")
    protected abstract void pruneNow(int maxLimit);

    @Query("UPDATE checklist_history SET stateJson = :stateJson, addedShoppingKeysJson = :addedShoppingKeysJson, status = COALESCE(:status, status) WHERE userId = " + ScopeSql.USER + " AND id = :id")
    protected abstract void updateStateNow(@NonNull String id, @NonNull String stateJson,
            @NonNull String addedShoppingKeysJson, @Nullable String status);

    @Query("UPDATE checklist_history SET stateJson = :stateJson WHERE userId = " + ScopeSql.USER + " AND id = :id AND stateJson = ''")
    protected abstract void backfillStateNow(@NonNull String id, @NonNull String stateJson);

    @Query("UPDATE checklist_history SET status = :status WHERE userId = " + ScopeSql.USER + " AND id = :id")
    protected abstract void updateStatusNow(@NonNull String id, @NonNull String status);

    @Query("UPDATE checklist_history SET addedShoppingKeysJson = :addedShoppingKeysJson WHERE userId = " + ScopeSql.USER + " AND id = :id")
    protected abstract void updateAddedShoppingKeysNow(@NonNull String id, @NonNull String addedShoppingKeysJson);

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id IN (SELECT id FROM checklist_history WHERE userId = " + ScopeSql.USER + " ORDER BY created_at ASC LIMIT :count)")
    protected abstract void deleteOldestNow(int count);

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER + " AND id = :id")
    protected abstract void deleteByIdNow(@NonNull String id);

    @Query("DELETE FROM checklist_history WHERE userId = " + ScopeSql.USER)
    protected abstract void clearAllNow();

    @Transaction
    protected void insertNow(@NonNull ChecklistHistoryItem item) {
        DataScope scope = requireScopeNow();
        scope.requireDraft(item.getUserId());
        ChecklistHistoryItem existing = readById(item.getId());
        ChecklistHistoryItem owned = item.copy(item.getId(), item.getDishName(), item.getServings(), item.getStatus(),
                item.getCreatedAt(), item.getMissingChecklistJson(), item.getInventoryDeductedJson(),
                item.getAddedShoppingKeysJson(), item.getStateJson(), scope.getUserId());
        if (existing == null) {
            insertRaw(owned);
        } else {
            updateRaw(owned);
        }
    }

    @Transaction
    protected void updateNow(@NonNull ChecklistHistoryItem item) {
        ChecklistHistoryItem existing = readById(item.getId());
        if (existing == null) {
            return;
        }
        if (!item.getUserId().equals(existing.getUserId())) {
            throw new IllegalArgumentException("Cannot change history ownership.");
        }
        updateRaw(item);
    }

    @Transaction
    protected void saveNow(@NonNull ChecklistHistoryItem item, int maxLimit) {
        insertNow(item);
        pruneNow(Math.max(1, Math.min(maxLimit, 10)));
    }

    @Override
    @NonNull
    public final Object getAllChecklistHistorySync(@NonNull Continuation<? super List<ChecklistHistoryItem>> continuation) {
        return execute(false, this::readRecent, continuation);
    }

    @Override
    @Nullable
    public final Object getChecklistHistoryById(@NonNull String id,
            @NonNull Continuation<? super ChecklistHistoryItem> continuation) {
        return execute(false, () -> readById(id), continuation);
    }

    @NonNull
    public final Object getCount(@NonNull Continuation<? super Integer> continuation) {
        return execute(false, this::countRows, continuation);
    }

    @NonNull
    public final Object insert(@NonNull ChecklistHistoryItem item,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> insertNow(item), continuation);
    }

    @NonNull
    public final Object update(@NonNull ChecklistHistoryItem item,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> updateNow(item), continuation);
    }

    @Override
    @NonNull
    public final Object saveWithPruning(@NonNull ChecklistHistoryItem item, int maxLimit,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> saveNow(item, maxLimit), continuation);
    }

    @NonNull
    public final Object saveWithPruning(@NonNull ChecklistHistoryItem item,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> saveNow(item, 10), continuation);
    }

    @Override
    @NonNull
    public final Object pruneToLatest(int maxLimit,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> pruneNow(maxLimit), continuation);
    }

    @NonNull
    public final Object pruneToLatest(@NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> pruneNow(10), continuation);
    }

    @Override
    @NonNull
    public final Object updateState(@NonNull String id,
            @NonNull String stateJson,
            @NonNull String addedShoppingKeysJson,
            @Nullable String status,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> updateStateNow(id, stateJson, addedShoppingKeysJson, status), continuation);
    }

    @NonNull
    public final Object updateState(@NonNull String id, @NonNull String stateJson, @NonNull String addedShoppingKeysJson,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> updateStateNow(id, stateJson, addedShoppingKeysJson, null), continuation);
    }

    @Override
    @NonNull
    public final Object backfillState(@NonNull String id, @NonNull String stateJson,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> backfillStateNow(id, stateJson), continuation);
    }

    @Override
    @NonNull
    public final Object updateStatus(@NonNull String id, @NonNull String status,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> updateStatusNow(id, status), continuation);
    }

    @Override
    @NonNull
    public final Object updateAddedShoppingKeys(@NonNull String id, @NonNull String addedShoppingKeysJson,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> updateAddedShoppingKeysNow(id, addedShoppingKeysJson), continuation);
    }

    @NonNull
    public final Object deleteOldest(int count,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteOldestNow(count), continuation);
    }

    @Override
    @NonNull
    public final Object deleteById(@NonNull String id,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteByIdNow(id), continuation);
    }

    @Override
    @NonNull
    public final Object clearAll(@NonNull Continuation<? super Unit> continuation) {
        return executeWrite(this::clearAllNow, continuation);
    }
}

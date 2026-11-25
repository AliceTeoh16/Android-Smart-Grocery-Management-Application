package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;
import androidx.room.Update;

import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;
import com.xmum.smartgrocery.utils.ChecklistShoppingMerge;
import com.xmum.smartgrocery.utils.ShoppingVoiceMerge;
import com.xmum.smartgrocery.utils.VoiceCommand;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.flow.Flow;

@Dao
public abstract class ShoppingListDao extends ScopedDao {
    protected ShoppingListDao(@NonNull RoomDatabase database) {
        super(database);
    }

    @Nullable
    @Query("SELECT displayName FROM users WHERE userId = " + ScopeSql.USER)
    protected abstract String actorName();

    @NonNull
    @Query("SELECT * FROM shopping_list_items WHERE " + ScopeSql.MATCH + " ORDER BY isChecked ASC, assignedTo ASC, updatedAt DESC")
    public abstract Flow<List<ShoppingListItem>> getShoppingListItems();

    @Nullable
    @Query("SELECT * FROM shopping_list_items WHERE (sourceItemId = :sourceItemId) AND " + ScopeSql.MATCH + " LIMIT 1")
    protected abstract ShoppingListItem readBySourceItemId(int sourceItemId);

    @NonNull
    @Query("SELECT * FROM shopping_list_items WHERE (sourceItemId IS NULL AND isChecked = 0 AND assignedTo = :assignedTo) AND " + ScopeSql.MATCH)
    protected abstract List<ShoppingListItem> readOpenManualItemsForAssignee(@NonNull String assignedTo);

    @NonNull
    @Query("SELECT * FROM shopping_list_items WHERE (isChecked = 0 AND assignedTo = :assignedTo) AND " + ScopeSql.MATCH + " ORDER BY id")
    protected abstract List<ShoppingListItem> readOpenItemsForAssignee(@NonNull String assignedTo);

    @Insert
    protected abstract long insertManualRaw(@NonNull ShoppingListItem item);

    @Nullable
    @Query("SELECT id FROM grocery_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract Integer validSource(int id);

    @Nullable
    @Query("SELECT * FROM shopping_list_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract ShoppingListItem readById(int id);

    @Update
    protected abstract int updateRaw(@NonNull ShoppingListItem item);

    @Query("DELETE FROM shopping_list_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract int deleteScoped(int id);

    @Query("UPDATE shopping_list_items SET isChecked = :isChecked, updatedAt = :updatedAt, lastModifiedByName = CASE WHEN householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedAction = CASE WHEN householdId IS NOT NULL THEN CASE WHEN :isChecked THEN 'Checked' ELSE 'Unchecked' END ELSE NULL END, lastModifiedAt = CASE WHEN householdId IS NOT NULL THEN :updatedAt ELSE NULL END WHERE (id = :id) AND " + ScopeSql.MATCH)
    protected abstract void writeChecked(int id, boolean isChecked, long updatedAt);

    @Nullable
    @Query("SELECT * FROM shopping_list_items WHERE id = :id AND " + ScopeSql.ACCESSIBLE)
    protected abstract ShoppingListItem accessibleById(int id);

    @Query("UPDATE shopping_list_items SET addedByName = CASE WHEN :householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedByName = CASE WHEN :householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + " ELSE NULL END, lastModifiedAction = CASE WHEN :householdId IS NOT NULL THEN 'Added' ELSE NULL END, lastModifiedAt = CASE WHEN :householdId IS NOT NULL THEN :updatedAt ELSE NULL END, userId = :userId, householdId = :householdId, sourceItemId = NULL, assignedTo = :assignedTo, updatedAt = :updatedAt WHERE id = :id AND " + ScopeSql.ACCESSIBLE)
    protected abstract void moveToScope(int id, @NonNull String userId, @Nullable String householdId,
            @NonNull String assignedTo, long updatedAt);

    @Query("DELETE FROM shopping_list_items WHERE (isChecked = 1) AND " + ScopeSql.MATCH)
    protected abstract void deleteCheckedNow();

    @Query("DELETE FROM shopping_list_items WHERE (sourceItemId = :sourceItemId) AND " + ScopeSql.MATCH)
    protected abstract void deleteBySourceItemIdNow(int sourceItemId);

    @Query("DELETE FROM shopping_list_items WHERE addedBy = 'Auto' AND sourceItemId IS NOT NULL AND isChecked = 0 AND " + ScopeSql.ACCESSIBLE)
    protected abstract int removeUnapprovedRestocksNow();

    @Nullable
    @Query("SELECT * FROM grocery_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract GroceryItem restockSource(int id);

    @NonNull
    @Query("SELECT COUNT(*) FROM shopping_list_items WHERE addedBy = 'Auto' AND sourceItemId IS NOT NULL AND isChecked = 0 AND " + ScopeSql.ACCESSIBLE)
    public abstract Flow<Integer> observeUnapprovedRestockCount();

    @NonNull
    @Query("SELECT sourceItemId FROM shopping_list_items WHERE (sourceItemId IS NOT NULL) AND " + ScopeSql.MATCH)
    protected abstract List<Integer> readAutoSourceIds();

    /** Re-reads matches in one transaction to prevent duplicate rows during concurrent additions. */
    @Transaction
    protected void applyVoiceItems(@NonNull List<VoiceCommand> commands) {
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("Review at least one shopping item.");
        }
        for (VoiceCommand command : commands) {
            String target = assignee(requireScopeNow());
            applyPlan(ShoppingVoiceMerge.merge(readOpenItemsForAssignee(target), command, target));
        }
    }

    @Transaction
    protected void applyChecklistItems(@NonNull List<ChecklistShoppingMerge.Addition> items) {
        for (ChecklistShoppingMerge.Addition addition : items) {
            String target = assignee(requireScopeNow());
            applyPlan(ChecklistShoppingMerge.merge(readOpenItemsForAssignee(target), addition, target));
        }
    }

    @Transaction
    protected long insertManualNow(@NonNull ShoppingListItem item) {
        DataScope scope = requireScopeNow();
        if (item.getId() != 0) {
            throw new IllegalArgumentException("Use update for existing shopping items.");
        }
        scope.requireDraft(item.getUserId(), item.getHouseholdId());
        if ("Me".equals(item.getAssignedTo()) && scope.getHouseholdId() != null) {
            throw new IllegalArgumentException("Select personal context for a private shopping item.");
        }
        if (item.getSourceItemId() != null && validSource(item.getSourceItemId()) == null) {
            throw new IllegalArgumentException("Source inventory is outside the selected scope.");
        }
        String actor = scope.getHouseholdId() != null ? actorName() : null;
        return insertManualRaw(withAttribution(item.normalized(), scope, assignee(scope), actor, actor,
                item.isChecked() ? "Checked" : "Added"));
    }

    @Transaction
    protected int updateNow(@NonNull ShoppingListItem item) {
        ShoppingListItem old = readById(item.getId());
        if (old == null) {
            return 0;
        }
        if (!item.getUserId().equals(old.getUserId()) || !Objects.equals(item.getHouseholdId(), old.getHouseholdId())) {
            throw new IllegalArgumentException("Ownership cannot be changed by an edit.");
        }
        if (item.getSourceItemId() != null && !item.getSourceItemId().equals(old.getSourceItemId())) {
            throw new IllegalArgumentException("Cannot change inventory source.");
        }
        String actor = old.getHouseholdId() != null ? actorName() : null;
        String action = old.isChecked() == item.isChecked() ? "Updated" : item.isChecked() ? "Checked" : "Unchecked";
        return updateRaw(withAttribution(item.normalized(), new DataScope(item.getUserId(), item.getHouseholdId()),
                item.getAssignedTo(), old.getAddedByName(), actor, action));
    }

    @Transaction
    protected void assignToNow(int id, @NonNull String assignedTo, long updatedAt) {
        ShoppingListItem item = accessibleById(id);
        if (item == null) {
            return;
        }
        DataScope scope = requireScopeNow();
        if (!assignedTo.equals(assignee(scope))) {
            throw new IllegalArgumentException("Select the target data context first.");
        }
        if (!Objects.equals(item.getHouseholdId(), scope.getHouseholdId())) {
            // An explicit transfer drops the previous inventory link.
            moveToScope(id, scope.getUserId(), scope.getHouseholdId(), assignedTo, updatedAt);
        }
    }

    /** A refresh only removes obsolete suggestions. Approval is a separate write. */
    @Transaction
    protected void syncLowStockNow(@Nullable DataScope expectedScope) {
        DataScope scope = readScope();
        if (scope != null && (expectedScope == null || scope.equals(expectedScope))) {
            removeUnapprovedRestocksNow();
        }
    }

    /** Re-reads stock and approved shopping plans in the same transaction. */
    @Transaction
    protected int approveRestocksNow(@NonNull Map<Integer, Integer> quantities) {
        DataScope scope = requireScopeNow();
        String assignedTo = assignee(scope);
        int added = 0;
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            int id = entry.getKey();
            int quantity = entry.getValue();
            if (quantity < 1 || quantity > 9999) {
                throw new IllegalArgumentException("Choose a restock quantity from 1 to 9999.");
            }
            GroceryItem item = restockSource(id);
            if (item == null || item.getQuantity() > item.getThreshold()) {
                continue;
            }
            ShoppingListItem previous = readBySourceItemId(id);
            if (previous != null) {
                if (!previous.isChecked()) {
                    continue;
                }
                updateNow(withoutSource(previous));
            }
            String name = StringsKt.trim(item.getName()).toString();
            boolean hasMatch = readOpenItemsForAssignee(assignedTo).stream().anyMatch(plan ->
                    StringsKt.trim(plan.getItemName()).toString().equalsIgnoreCase(name));
            if (hasMatch) {
                continue;
            }
            insertManualNow(new ShoppingListItem(0, id, item.getName(), item.getCategory(), quantity, "",
                    item.getPrice(), "Restock", assignedTo, false, System.currentTimeMillis(), System.currentTimeMillis(),
                    item.getSubcategory(), "", null, null, null, null, null));
            added++;
        }
        return added;
    }

    private void applyPlan(ShoppingListItem planned) {
        if (planned.getId() == 0) {
            insertManualNow(planned);
        } else {
            updateNow(planned);
        }
    }

    private static String assignee(DataScope scope) {
        return scope.getHouseholdId() == null ? "Me" : "Household";
    }

    private static ShoppingListItem withAttribution(ShoppingListItem item, DataScope scope, String assignedTo,
            String creator, String actor, String action) {
        return item.copy(item.getId(), item.getSourceItemId(), item.getItemName(), item.getCategory(),
                item.getQuantityNeeded(), item.getQuantityLabel(), item.getEstimatedUnitPrice(), item.getAddedBy(),
                assignedTo, item.isChecked(), item.getCreatedAt(), item.getUpdatedAt(), item.getSubcategory(),
                scope.getUserId(), scope.getHouseholdId(), creator, actor, actor != null ? action : null,
                actor != null ? System.currentTimeMillis() : null);
    }

    private static ShoppingListItem withoutSource(ShoppingListItem item) {
        return item.copy(item.getId(), null, item.getItemName(), item.getCategory(), item.getQuantityNeeded(),
                item.getQuantityLabel(), item.getEstimatedUnitPrice(), item.getAddedBy(), item.getAssignedTo(),
                item.isChecked(), item.getCreatedAt(), item.getUpdatedAt(), item.getSubcategory(), item.getUserId(),
                item.getHouseholdId(), item.getAddedByName(), item.getLastModifiedByName(), item.getLastModifiedAction(),
                item.getLastModifiedAt());
    }

    @Nullable
    public final Object getBySourceItemId(int sourceItemId,
            @NonNull Continuation<? super ShoppingListItem> continuation) {
        return execute(false, () -> readBySourceItemId(sourceItemId), continuation);
    }

    @NonNull
    public final Object getOpenManualItemsForAssignee(@NonNull String assignedTo,
            @NonNull Continuation<? super List<ShoppingListItem>> continuation) {
        return execute(false, () -> readOpenManualItemsForAssignee(assignedTo), continuation);
    }

    @NonNull
    public final Object getOpenItemsForAssignee(@NonNull String assignedTo,
            @NonNull Continuation<? super List<ShoppingListItem>> continuation) {
        return execute(false, () -> readOpenItemsForAssignee(assignedTo), continuation);
    }

    @NonNull
    public final Object addVoiceItems(@NonNull List<VoiceCommand> commands, @NonNull String assignedTo,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> applyVoiceItems(commands), continuation);
    }

    @NonNull
    public final Object addAiChecklistItems(@NonNull List<ChecklistShoppingMerge.Addition> items, @NonNull String assignedTo,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> applyChecklistItems(items), continuation);
    }

    @NonNull
    public final Object insert(@NonNull ShoppingListItem item,
            @NonNull Continuation<? super Long> continuation) {
        return execute(true, () -> insertManualNow(item), continuation);
    }

    @NonNull
    public final Object insertManual(@NonNull ShoppingListItem item,
            @NonNull Continuation<? super Long> continuation) {
        return execute(true, () -> insertManualNow(item), continuation);
    }

    @Nullable
    public final Object getById(int id,
            @NonNull Continuation<? super ShoppingListItem> continuation) {
        return execute(false, () -> readById(id), continuation);
    }

    @NonNull
    public final Object update(@NonNull ShoppingListItem item,
            @NonNull Continuation<? super Integer> continuation) {
        return execute(true, () -> updateNow(item), continuation);
    }

    @NonNull
    public final Object delete(@NonNull ShoppingListItem item,
            @NonNull Continuation<? super Integer> continuation) {
        return execute(true, () -> deleteScoped(item.getId()), continuation);
    }

    @NonNull
    public final Object setChecked(int id, boolean isChecked, long updatedAt,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> writeChecked(id, isChecked, updatedAt), continuation);
    }

    @NonNull
    public final Object setChecked(int id, boolean isChecked,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> writeChecked(id, isChecked, System.currentTimeMillis()), continuation);
    }

    @NonNull
    public final Object assignTo(int id, @NonNull String assignedTo, long updatedAt,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> assignToNow(id, assignedTo, updatedAt), continuation);
    }

    @NonNull
    public final Object assignTo(int id, @NonNull String assignedTo,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> assignToNow(id, assignedTo, System.currentTimeMillis()), continuation);
    }

    @NonNull
    public final Object deleteChecked(@NonNull Continuation<? super Unit> continuation) {
        return executeWrite(this::deleteCheckedNow, continuation);
    }

    @NonNull
    public final Object deleteBySourceItemId(int sourceItemId,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteBySourceItemIdNow(sourceItemId), continuation);
    }

    @NonNull
    public final Object syncLowStockItems(@NonNull List<GroceryItem> items, @Nullable DataScope expectedScope,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> syncLowStockNow(expectedScope), continuation);
    }

    @NonNull
    public final Object syncLowStockItems(@NonNull List<GroceryItem> items,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> syncLowStockNow(null), continuation);
    }

    @NonNull
    public final Object removeUnapprovedRestocks(@NonNull Continuation<? super Integer> continuation) {
        return execute(true, this::removeUnapprovedRestocksNow, continuation);
    }

    @NonNull
    public final Object approveRestocks(@NonNull Map<Integer, Integer> quantities,
            @NonNull Continuation<? super Integer> continuation) {
        return execute(true, () -> approveRestocksNow(quantities), continuation);
    }

    @NonNull
    public final Object getAutoSourceIds(@NonNull Continuation<? super List<Integer>> continuation) {
        return execute(false, this::readAutoSourceIds, continuation);
    }
}

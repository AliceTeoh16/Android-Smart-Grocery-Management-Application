package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;
import androidx.room.Update;

import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.InventoryChangeType;
import com.xmum.smartgrocery.data.local.entity.InventoryLog;
import com.xmum.smartgrocery.utils.GroceryCategories;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.flow.Flow;

@Dao
public abstract class GroceryDao extends ScopedDao {
    protected GroceryDao(@NonNull RoomDatabase database) {
        super(database);
    }

    @Nullable
    @Query("SELECT displayName FROM users WHERE userId = " + ScopeSql.USER)
    protected abstract String actorName();

    @NonNull
    @Query("SELECT * FROM grocery_items WHERE " + ScopeSql.ACCESSIBLE + " ORDER BY id")
    public abstract Flow<List<GroceryItem>> observeAccessibleItems();

    @NonNull
    @Query("SELECT * FROM grocery_items WHERE " + ScopeSql.MATCH + " ORDER BY purchasedDate DESC")
    public abstract Flow<List<GroceryItem>> getAllItems();

    @NonNull
    @Query("SELECT * FROM grocery_items WHERE " + ScopeSql.MATCH + " ORDER BY purchasedDate DESC")
    protected abstract List<GroceryItem> readAllItems();

    @Nullable
    @Query("SELECT * FROM grocery_items WHERE (name LIKE :name) AND " + ScopeSql.MATCH + " LIMIT 1")
    protected abstract GroceryItem readItemByName(@NonNull String name);

    @Nullable
    @Query("SELECT * FROM grocery_items WHERE (LOWER(name) LIKE '%' || LOWER(:name) || '%' OR LOWER(:name) LIKE '%' "
            + "|| LOWER(name) || '%') AND " + ScopeSql.MATCH + " ORDER BY quantity DESC LIMIT 1")
    protected abstract GroceryItem readBestMatchingItem(@NonNull String name);

    @NonNull
    @Query("SELECT * FROM grocery_items WHERE (LOWER(name) LIKE '%' || LOWER(:name) || '%' OR LOWER(:name) LIKE '%' "
            + "|| LOWER(name) || '%') AND " + ScopeSql.MATCH + " ORDER BY CASE WHEN expiryDate IS NULL OR expiryDate <= 0 THEN 1 ELSE 0 END, expiryDate ASC, id ASC")
    protected abstract List<GroceryItem> readMatchingItems(@NonNull String name);

    @Nullable
    @Query("SELECT * FROM grocery_items WHERE (id = :id) AND " + ScopeSql.MATCH + " LIMIT 1")
    protected abstract GroceryItem readItemById(int id);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract long insertRaw(@NonNull GroceryItem item);

    @Insert
    protected abstract long insertLogRaw(@NonNull InventoryLog log);

    @Update
    protected abstract int updateRaw(@NonNull GroceryItem item);

    @Query("DELETE FROM grocery_items WHERE id = :id AND " + ScopeSql.MATCH)
    protected abstract int deleteScoped(int id);

    @Query("DELETE FROM grocery_items WHERE (expiryDate > 0 AND expiryDate < :today) AND " + ScopeSql.MATCH)
    protected abstract int deleteExpired(long today);

    @Query("UPDATE grocery_items SET quantity = :newQty, lastModifiedByName = CASE WHEN householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + "ELSE NULL END, lastModifiedAction = CASE WHEN householdId IS NOT NULL THEN 'Updated' ELSE NULL END, "
            + "lastModifiedAt = CASE WHEN householdId IS NOT NULL THEN :modifiedAt ELSE NULL END WHERE (id = :id) AND " + ScopeSql.MATCH)
    protected abstract void writeQuantity(int id, int newQty, long modifiedAt);

    @Query("UPDATE grocery_items SET quantity = CASE WHEN quantity - :quantityUsed < 0 THEN 0 ELSE quantity - "
            + ":quantityUsed END, lastModifiedByName = CASE WHEN householdId IS NOT NULL THEN " + ScopeSql.ACTOR_NAME + "ELSE NULL END, lastModifiedAction = CASE WHEN householdId IS NOT NULL THEN 'Updated' ELSE NULL END, "
            + "lastModifiedAt = CASE WHEN householdId IS NOT NULL THEN :modifiedAt ELSE NULL END WHERE (id = :itemId) "
            + "AND " + ScopeSql.MATCH)
    protected abstract void deductQuantityNow(int itemId, int quantityUsed, long modifiedAt);

    @NonNull
    @Query("SELECT * FROM grocery_items WHERE (quantity <= threshold) AND " + ScopeSql.MATCH)
    public abstract Flow<List<GroceryItem>> getLowStockItems();

    @NonNull
    @Query("SELECT * FROM inventory_logs WHERE (itemId = :itemId) AND " + ScopeSql.MATCH + " ORDER BY timestamp DESC")
    public abstract Flow<List<InventoryLog>> getLogsForItem(int itemId);

    /** Same activity as per-item observers, read once for the current inventory scope. */
    @NonNull
    @Query("SELECT * FROM inventory_logs WHERE " + ScopeSql.MATCH
            + " AND itemId IN (SELECT id FROM grocery_items WHERE " + ScopeSql.MATCH + ") ORDER BY timestamp DESC, id DESC")
    public abstract Flow<List<InventoryLog>> getInventoryActivityLogs();

    /** Historical snapshots survive item deletion and use the same scope as budget spending. */
    @NonNull
    @Query("SELECT * FROM inventory_logs WHERE changeType = 'ADD' AND amountChanged > 0 AND " + ScopeSql.MATCH + " ORDER BY timestamp DESC, id DESC")
    public abstract Flow<List<InventoryLog>> getPurchaseHistory();

    @Transaction
    protected long insertItem(@NonNull GroceryItem item) {
        DataScope scope = requireScopeNow();
        if (item.getId() != 0) {
            throw new IllegalArgumentException("Use update for existing inventory.");
        }
        scope.requireDraft(item.getUserId(), item.getHouseholdId());
        String actor = scope.getHouseholdId() == null ? null : actorName();
        return insertRaw(withAttribution(item.normalized(), scope.getUserId(), scope.getHouseholdId(), actor, actor, "Added"));
    }

    @Transaction
    protected long addLog(@NonNull InventoryLog log) {
        GroceryItem item = log.getItemId() == null ? null : readItemById(log.getItemId());
        if (item == null) {
            throw new IllegalArgumentException("Inventory logs must reference accessible inventory.");
        }
        return insertLogRaw(log.copy(log.getId(), log.getItemId(), log.getItemName(), log.getCategory(),
                log.getChangeType(), log.getAmountChanged(), log.getUnitPriceAtChange(), log.getTimestamp(),
                item.getUserId(), item.getHouseholdId(), log.getUnit()));
    }

    @Transaction
    protected long addItemWithLog(@NonNull GroceryItem item) {
        int id = (int) insertItem(item);
        if (item.getQuantity() > 0) {
            logChange(item, id, InventoryChangeType.ADD, item.getQuantity(), item.getPurchasedDate());
        }
        return id;
    }

    @Transaction
    protected int updateItem(@NonNull GroceryItem item) {
        GroceryItem previous = readItemById(item.getId());
        if (previous == null) {
            return 0;
        }
        if (!item.getUserId().equals(previous.getUserId())
                || !Objects.equals(item.getHouseholdId(), previous.getHouseholdId())) {
            throw new IllegalArgumentException("Ownership cannot be changed by an edit.");
        }
        String actor = previous.getHouseholdId() == null ? null : actorName();
        return updateRaw(withAttribution(item.normalized(), item.getUserId(), item.getHouseholdId(),
                previous.getAddedByName(), actor, "Updated"));
    }

    /** An outer Room transaction covers every item and its inventory log. */
    @Transaction
    @NonNull
    protected List<Long> addBatchWithLogs(@NonNull List<GroceryItem> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Select at least one item.");
        }
        for (GroceryItem item : items) {
            if (item.getId() != 0 || StringsKt.isBlank(item.getName()) || item.getQuantity() <= 0
                    || !Double.isFinite(item.getPrice()) || item.getPrice() < 0.0) {
                throw new IllegalArgumentException("Review all item names and quantities before adding.");
            }
        }
        List<Long> ids = new ArrayList<>(items.size());
        for (GroceryItem item : items) {
            ids.add(addItemWithLog(item));
        }
        return ids;
    }

    @Transaction
    protected void writeQuantityWithLog(int id, int newQty, @Nullable String changeType, long timestamp) {
        GroceryItem item = readItemById(id);
        if (item == null) {
            return;
        }
        int safeQty = Math.max(newQty, 0);
        int delta = safeQty - item.getQuantity();
        writeQuantity(id, safeQty, System.currentTimeMillis());
        if (delta != 0) {
            String type = changeType != null ? changeType : delta > 0 ? InventoryChangeType.ADD : InventoryChangeType.DEDUCT;
            logChange(item, id, type, Math.abs(delta), timestamp);
        }
    }

    @Transaction
    protected void applyRecipeDeductions(@NonNull List<RecipeDeductUpdate> updates) {
        for (RecipeDeductUpdate update : updates) {
            deductAndLog(update.getItemId(), update.getQuantityUsed(), InventoryChangeType.RECIPE_DEDUCT,
                    System.currentTimeMillis());
        }
    }

    @Transaction
    protected void deductAndLog(int itemId, int quantityUsed, @NonNull String changeType, long timestamp) {
        GroceryItem item = readItemById(itemId);
        if (item == null) {
            return;
        }
        int deduction = Math.min(Math.max(quantityUsed, 0), item.getQuantity());
        if (deduction <= 0) {
            return;
        }
        deductQuantityNow(itemId, deduction, System.currentTimeMillis());
        logChange(item, itemId, changeType, deduction, timestamp);
    }

    @Transaction
    protected void consumeEarliestBatches(@NonNull String name, int quantityUsed) {
        int remaining = Math.max(quantityUsed, 0);
        if (remaining == 0) {
            return;
        }
        for (GroceryItem batch : readMatchingItems(name)) {
            if (remaining == 0) {
                break;
            }
            if (batch.getQuantity() > 0) {
                int consumed = Math.min(batch.getQuantity(), remaining);
                writeQuantityWithLog(batch.getId(), batch.getQuantity() - consumed, null, System.currentTimeMillis());
                remaining -= consumed;
            }
        }
    }

    private void logChange(GroceryItem item, int id, String type, int amount, long timestamp) {
        addLog(new InventoryLog(0, id, item.getName(), GroceryCategories.mainCategory(item.getCategory()).getLabel(),
                type, amount, item.getPrice(), timestamp, "", null, item.getUnit()));
    }

    private static GroceryItem withAttribution(GroceryItem item, String userId, String householdId,
            String creator, String actor, String action) {
        return item.copy(item.getId(), item.getName(), item.getQuantity(), item.getPrice(), item.getCategory(),
                item.getExpiryDate(), item.getThreshold(), item.getPurchasedDate(), item.getSubcategory(),
                userId, householdId, creator, actor, actor != null ? action : null,
                actor != null ? System.currentTimeMillis() : null, item.getUnit(), item.getStorageLocation());
    }

    @NonNull
    public final Object getAllItemsOnce(@NonNull Continuation<? super List<GroceryItem>> continuation) {
        return execute(false, this::readAllItems, continuation);
    }

    @Nullable
    public final Object getItemByName(@NonNull String name,
            @NonNull Continuation<? super GroceryItem> continuation) {
        return execute(false, () -> readItemByName(name), continuation);
    }

    @Nullable
    public final Object findBestMatchingItem(@NonNull String name,
            @NonNull Continuation<? super GroceryItem> continuation) {
        return execute(false, () -> readBestMatchingItem(name), continuation);
    }

    @NonNull
    public final Object findMatchingItems(@NonNull String name,
            @NonNull Continuation<? super List<GroceryItem>> continuation) {
        return execute(false, () -> readMatchingItems(name), continuation);
    }

    @Nullable
    public final Object getItemById(int id,
            @NonNull Continuation<? super GroceryItem> continuation) {
        return execute(false, () -> readItemById(id), continuation);
    }

    @NonNull
    public final Object insert(@NonNull GroceryItem item,
            @NonNull Continuation<? super Long> continuation) {
        return execute(true, () -> insertItem(item), continuation);
    }

    @NonNull
    public final Object insertLog(@NonNull InventoryLog log,
            @NonNull Continuation<? super Long> continuation) {
        return execute(true, () -> addLog(log), continuation);
    }

    @NonNull
    public final Object insertWithLog(@NonNull GroceryItem item,
            @NonNull Continuation<? super Long> continuation) {
        return execute(true, () -> addItemWithLog(item), continuation);
    }

    @NonNull
    public final Object update(@NonNull GroceryItem item,
            @NonNull Continuation<? super Integer> continuation) {
        return execute(true, () -> updateItem(item), continuation);
    }

    @NonNull
    public final Object insertBatchWithLogs(@NonNull List<GroceryItem> items,
            @NonNull Continuation<? super List<Long>> continuation) {
        return execute(true, () -> addBatchWithLogs(items), continuation);
    }

    @NonNull
    public final Object delete(@NonNull GroceryItem item,
            @NonNull Continuation<? super Integer> continuation) {
        return execute(true, () -> deleteScoped(item.getId()), continuation);
    }

    @NonNull
    public final Object deleteExpiredItems(long today,
            @NonNull Continuation<? super Integer> continuation) {
        return execute(true, () -> deleteExpired(today), continuation);
    }

    @NonNull
    public final Object updateQuantity(int id, int newQty, long modifiedAt,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> writeQuantity(id, newQty, modifiedAt), continuation);
    }

    @NonNull
    public final Object updateQuantityWithLog(int id, int newQty, @Nullable String changeType, long timestamp,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> writeQuantityWithLog(id, newQty, changeType, timestamp), continuation);
    }

    @NonNull
    public final Object deductRecipeItems(@NonNull List<RecipeDeductUpdate> updates,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> applyRecipeDeductions(updates), continuation);
    }

    @NonNull
    public final Object deductQuantityRaw(int itemId, int quantityUsed, long modifiedAt,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deductQuantityNow(itemId, quantityUsed, modifiedAt), continuation);
    }

    @NonNull
    public final Object deductQuantity(int itemId, int quantityUsed, @NonNull String changeType, long timestamp,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deductAndLog(itemId, quantityUsed, changeType, timestamp), continuation);
    }

    @NonNull
    public final Object consumeQuantityFefo(@NonNull String name, int quantityUsed,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> consumeEarliestBatches(name, quantityUsed), continuation);
    }

    /** UI cards must never mix grams, pieces, packages, or storage locations. */
    @NonNull
    public final Object consumeCardQuantity(@NonNull String name, @NonNull String unit,
            @NonNull String storage, int quantityUsed, @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> {
            int remaining = Math.max(quantityUsed, 0);
            for (GroceryItem batch : readMatchingItems(name)) {
                if (remaining == 0) break;
                if (!batch.getName().trim().equalsIgnoreCase(name.trim()) || !batch.getUnit().equals(unit)
                        || !batch.getStorageLocation().equals(storage)) continue;
                int consumed = Math.min(Math.max(batch.getQuantity(), 0), remaining);
                if (consumed > 0) writeQuantityWithLog(batch.getId(), batch.getQuantity() - consumed, null, System.currentTimeMillis());
                remaining -= consumed;
            }
        }, continuation);
    }

    @NonNull
    public final Object updateQuantity(int id, int newQty, @NonNull Continuation<? super Unit> continuation) {
        return updateQuantity(id, newQty, System.currentTimeMillis(), continuation);
    }

    @NonNull
    public final Object updateQuantityWithLog(int id, int newQty, @NonNull Continuation<? super Unit> continuation) {
        return updateQuantityWithLog(id, newQty, null, System.currentTimeMillis(), continuation);
    }

    @NonNull
    public final Object deductQuantity(int id, int quantityUsed, @NonNull Continuation<? super Unit> continuation) {
        return deductQuantity(id, quantityUsed, InventoryChangeType.DEDUCT, System.currentTimeMillis(), continuation);
    }

    @NonNull
    public final Object updateWithoutResult(@NonNull GroceryItem item,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> updateItem(item), continuation);
    }

    @NonNull
    public final Object deleteWithoutResult(@NonNull GroceryItem item,
            @NonNull Continuation<? super Unit> continuation) {
        return executeWrite(() -> deleteScoped(item.getId()), continuation);
    }
}

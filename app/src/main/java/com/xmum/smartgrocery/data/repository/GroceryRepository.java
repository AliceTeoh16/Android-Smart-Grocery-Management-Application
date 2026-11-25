package com.xmum.smartgrocery.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.dao.GroceryDao;
import com.xmum.smartgrocery.data.local.dao.RecipeDeductUpdate;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.InventoryChangeType;
import com.xmum.smartgrocery.data.local.entity.InventoryLog;

import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

public final class GroceryRepository {
    private final GroceryDao groceryDao;
    private final Flow<List<GroceryItem>> allGroceryItems;
    private final Flow<List<GroceryItem>> lowStockItems;
    private final Flow<List<InventoryLog>> purchaseHistory;

    public GroceryRepository(@NonNull GroceryDao groceryDao) {
        this.groceryDao = groceryDao;
        allGroceryItems = FlowKt.distinctUntilChanged(groceryDao.getAllItems());
        lowStockItems = FlowKt.distinctUntilChanged(groceryDao.getLowStockItems());
        purchaseHistory = FlowKt.distinctUntilChanged(groceryDao.getPurchaseHistory());
    }

    @NonNull
    public Flow<List<GroceryItem>> getAllGroceryItems() {
        return allGroceryItems;
    }

    @NonNull
    public Flow<List<GroceryItem>> getLowStockItems() {
        return lowStockItems;
    }

    @NonNull
    public Flow<List<InventoryLog>> getPurchaseHistory() {
        return purchaseHistory;
    }

    @NonNull
    public Object insert(@NonNull GroceryItem item, @NonNull Continuation<? super Long> continuation) {
        return groceryDao.insertWithLog(item, continuation);
    }

    @NonNull
    public Object insertBatch(@NonNull List<GroceryItem> items,
            @NonNull Continuation<? super List<Long>> continuation) {
        return groceryDao.insertBatchWithLogs(items, continuation);
    }

    @NonNull
    public Object update(@NonNull GroceryItem item, @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.updateWithoutResult(item, continuation);
    }

    @NonNull
    public Object delete(@NonNull GroceryItem item, @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.deleteWithoutResult(item, continuation);
    }

    @NonNull
    public Object updateQuantity(int id, int newQty, @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.updateQuantityWithLog(id, newQty, continuation);
    }

    @NonNull
    public Object updateQuantityFromVoiceAdd(int id, int newQty, @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.updateQuantityWithLog(id, newQty, InventoryChangeType.ADD,
                System.currentTimeMillis(), continuation);
    }

    @NonNull
    public Object updateQuantityFromVoiceDeduct(int id, int newQty, @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.updateQuantityWithLog(id, newQty, InventoryChangeType.VOICE_DEDUCT,
                System.currentTimeMillis(), continuation);
    }

    @NonNull
    public Object deductRecipeItems(@NonNull List<RecipeDeductUpdate> updates,
            @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.deductRecipeItems(updates, continuation);
    }

    @Nullable
    public Object getItemByName(@NonNull String name, @NonNull Continuation<? super GroceryItem> continuation) {
        return groceryDao.getItemByName(name, continuation);
    }

    @Nullable
    public Object findBestMatchingItem(@NonNull String name, @NonNull Continuation<? super GroceryItem> continuation) {
        return groceryDao.findBestMatchingItem(name, continuation);
    }

    @NonNull
    public Object findMatchingItems(@NonNull String name,
            @NonNull Continuation<? super List<GroceryItem>> continuation) {
        return groceryDao.findMatchingItems(name, continuation);
    }

    @NonNull
    public Object consumeQuantityFefo(@NonNull String name, int quantityUsed,
            @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.consumeQuantityFefo(name, quantityUsed, continuation);
    }

    @NonNull
    public Object consumeCardQuantity(@NonNull String name, @NonNull String unit, @NonNull String storage,
            int quantityUsed, @NonNull Continuation<? super Unit> continuation) {
        return groceryDao.consumeCardQuantity(name, unit, storage, quantityUsed, continuation);
    }

    @NonNull
    public Object deleteExpiredItems(long today, @NonNull Continuation<? super Integer> continuation) {
        return groceryDao.deleteExpiredItems(today, continuation);
    }

    @Nullable
    public Object getItemById(int id, @NonNull Continuation<? super GroceryItem> continuation) {
        return groceryDao.getItemById(id, continuation);
    }

    @NonNull
    public Object getAllItemsOnce(@NonNull Continuation<? super List<GroceryItem>> continuation) {
        return groceryDao.getAllItemsOnce(continuation);
    }

    @NonNull
    public Flow<List<InventoryLog>> getLogsForItem(int itemId) {
        return groceryDao.getLogsForItem(itemId);
    }

    @NonNull
    public Flow<List<InventoryLog>> getInventoryActivityLogs() {
        return FlowKt.distinctUntilChanged(groceryDao.getInventoryActivityLogs());
    }
}

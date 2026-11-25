package com.xmum.smartgrocery.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.dao.ShoppingListDao;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;
import com.xmum.smartgrocery.data.session.SessionManager;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.ChecklistShoppingMerge;
import com.xmum.smartgrocery.utils.SuspendCall;
import com.xmum.smartgrocery.utils.VoiceCommand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

public final class ShoppingListRepository {
    private final ShoppingListDao dao;
    private final SessionManager sessions;
    private final Flow<List<ShoppingListItem>> shoppingListItems;

    public ShoppingListRepository(@NonNull ShoppingListDao dao) {
        this(dao, null);
    }

    public ShoppingListRepository(@NonNull ShoppingListDao dao, @Nullable SessionManager sessions) {
        this.dao = dao;
        this.sessions = sessions;
        shoppingListItems = dao.getShoppingListItems();
    }

    @NonNull
    public Flow<List<ShoppingListItem>> getShoppingListItems() {
        return shoppingListItems;
    }

    @NonNull
    public Object syncLowStockItems(@NonNull List<GroceryItem> items, @NonNull Continuation<? super Unit> completion) {
        return dao.syncLowStockItems(items, completion);
    }

    @NonNull
    public Object syncLowStockItems(@NonNull List<GroceryItem> items, @Nullable DataScope expectedScope,
            @NonNull Continuation<? super Unit> completion) {
        return dao.syncLowStockItems(items, expectedScope, completion);
    }

    @NonNull
    public Object addVoiceItems(@NonNull List<VoiceCommand> commands, @NonNull String assignedTo,
            @NonNull Continuation<? super Unit> completion) {
        return inTarget(assignedTo, next -> dao.addVoiceItems(commands, assignedTo, next), completion);
    }

    @NonNull
    public Object addManualItem(@NonNull String itemName, @NonNull String category, int quantityNeeded,
            double estimatedUnitPrice, @NonNull String assignedTo, @NonNull Continuation<? super Unit> completion) {
        return addManualItem(itemName, category, quantityNeeded, estimatedUnitPrice, assignedTo, "", completion);
    }

    @NonNull
    public Object addManualItem(@NonNull String itemName, @NonNull String category, int quantityNeeded,
            double estimatedUnitPrice, @NonNull String assignedTo, @NonNull String quantityLabel,
            @NonNull Continuation<? super Unit> completion) {
        return inTarget(assignedTo, next -> {
            ShoppingListItem item = new ShoppingListItem(0, null, itemName, category, Math.max(1, quantityNeeded),
                    quantityLabel, Math.max(0.0, estimatedUnitPrice), "Manual", assignedTo, false,
                    System.currentTimeMillis(), System.currentTimeMillis(), null, "", null, null, null, null, null);
            return AndroidCoroutines.<Long, Unit>then(stored -> dao.insertManual(item, stored),
                    (id, unused) -> Unit.INSTANCE, next);
        }, completion);
    }

    @NonNull
    public Object addAiChecklistItem(@NonNull String itemName, @NonNull String category,
            @NonNull String recipeQuantityLabel, @NonNull String assignedTo,
            @NonNull Continuation<? super Boolean> completion) {
        List<ChecklistShoppingMerge.Addition> additions = Collections.singletonList(
                new ChecklistShoppingMerge.Addition(itemName, category, recipeQuantityLabel));
        return AndroidCoroutines.<Unit, Boolean>then(next -> addChecklistItems(additions, assignedTo, next),
                (unused, resumed) -> Boolean.TRUE, completion);
    }

    @NonNull
    public Object addAiChecklistItems(@NonNull List<RecipeRepository.MissingChecklistItem> items,
            @NonNull String assignedTo, @NonNull Continuation<? super Unit> completion) {
        List<ChecklistShoppingMerge.Addition> additions = new ArrayList<>(items.size());
        for (RecipeRepository.MissingChecklistItem item : items) {
            additions.add(new ChecklistShoppingMerge.Addition(item.getItem(), item.getCategory(), item.getNeededQuantity()));
        }
        return addChecklistItems(additions, assignedTo, completion);
    }

    @NonNull
    public Object setChecked(@NonNull ShoppingListItem item, boolean checked,
            @NonNull Continuation<? super Unit> completion) {
        return dao.setChecked(item.getId(), checked, completion);
    }

    @NonNull
    public Object assignTo(@NonNull ShoppingListItem item, @NonNull String assignee,
            @NonNull Continuation<? super Unit> completion) {
        return inTarget(assignee, next -> dao.assignTo(item.getId(), assignee, next), completion);
    }

    @NonNull
    public Object delete(@NonNull ShoppingListItem item, @NonNull Continuation<? super Unit> completion) {
        return AndroidCoroutines.<Integer, Unit>then(next -> dao.delete(item, next),
                (rows, unused) -> Unit.INSTANCE, completion);
    }

    @NonNull
    public Object deleteChecked(@NonNull Continuation<? super Unit> completion) {
        return dao.deleteChecked(completion);
    }

    private Object addChecklistItems(List<ChecklistShoppingMerge.Addition> additions, String assignedTo,
            Continuation<? super Unit> completion) {
        return inTarget(assignedTo, next -> dao.addAiChecklistItems(additions, assignedTo, next), completion);
    }

    private Object inTarget(String assignedTo, SuspendCall<Unit> operation, Continuation<? super Unit> completion) {
        return sessions != null ? sessions.runInShoppingScope(assignedTo, operation, completion)
                : operation.invoke(completion);
    }
}

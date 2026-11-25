package com.xmum.smartgrocery.ui.shopping;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelKt;
import androidx.room.RoomDatabaseKt;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.entity.BudgetEntry;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;
import com.xmum.smartgrocery.data.repository.AppPreferencesRepository;
import com.xmum.smartgrocery.data.repository.BudgetRepository;
import com.xmum.smartgrocery.data.repository.GroceryRepository;
import com.xmum.smartgrocery.data.repository.ShoppingListRepository;
import com.xmum.smartgrocery.data.session.SessionManager;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.DateUtils;
import com.xmum.smartgrocery.utils.ExpiryEstimator;
import com.xmum.smartgrocery.utils.GroceryCategories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.StateFlow;

public final class ShoppingListViewModel extends AndroidViewModel {
    private static final String[] PANTRY_WORDS = {
            "pantry", "seasoning", "seasonings", "spice", "spices", "salt", "sugar", "pepper",
            "soy sauce", "sauce", "oil", "vinegar", "flour", "starch", "cornstarch", "powder"
    };

    private final AppDatabase database;
    private final GroceryRepository groceries;
    private final ShoppingListRepository shopping;
    private final SessionManager sessions;
    private final AppPreferencesRepository preferences;
    private final CoroutineScope scope;
    private final MutableSharedFlow<String> outgoingMessages = SharedFlowKt.MutableSharedFlow(0, 0, BufferOverflow.SUSPEND);
    private final SharedFlow<String> messages = FlowKt.asSharedFlow(outgoingMessages);
    private final StateFlow<ShoppingMemberFilter> activeMemberFilter;
    private final StateFlow<List<ShoppingListItem>> shoppingListItems;
    private final StateFlow<Integer> expiryWarningDays;
    private final StateFlow<ShoppingBudgetImpact> budgetImpact;

    public ShoppingListViewModel(@NonNull Application application) {
        super(application);
        SmartGroceryApp app = (SmartGroceryApp) application;
        database = app.getDatabase();
        groceries = app.getGroceryRepository();
        shopping = app.getShoppingListRepository();
        sessions = app.getSessionManager();
        preferences = app.getPreferencesRepository();
        scope = ViewModelKt.getViewModelScope(this);
        BudgetRepository budget = app.getBudgetRepository();
        SharingStarted whileSubscribed = SharingStarted.Companion.WhileSubscribed(5000L, Long.MAX_VALUE);

        activeMemberFilter = FlowKt.stateIn(FlowKt.map(sessions.getSessions(), (value, ignored) ->
                value == null || value.getHouseholdId() == null ? ShoppingMemberFilter.PERSONAL : ShoppingMemberFilter.HOUSEHOLD),
                scope, SharingStarted.Companion.getEagerly(), ShoppingMemberFilter.PERSONAL);
        // Room's query enforces the selected personal or household scope.
        shoppingListItems = FlowKt.stateIn(shopping.getShoppingListItems(), scope, whileSubscribed, Collections.emptyList());
        expiryWarningDays = preferences.getExpiryWarningDays();
        Flow<ShoppingBudgetImpact> impact = FlowKt.combine(shoppingListItems,
                budget.getBudgetForMonth(DateUtils.getCurrentMonthYear()),
                budget.getTotalSpentForMonth(DateUtils.getStartOfMonth(), DateUtils.getEndOfMonth()),
                (items, limit, spent, ignored) -> budgetImpact(items, limit, spent));
        budgetImpact = FlowKt.stateIn(AndroidCoroutines.background(impact), scope, whileSubscribed, new ShoppingBudgetImpact());
    }

    @NonNull
    public SharedFlow<String> getMessages() { return messages; }

    @NonNull
    public StateFlow<ShoppingMemberFilter> getActiveMemberFilter() { return activeMemberFilter; }

    @NonNull
    public StateFlow<List<ShoppingListItem>> getAllShoppingListItems() { return shoppingListItems; }

    @NonNull
    public StateFlow<List<ShoppingListItem>> getShoppingListItems() { return shoppingListItems; }

    @NonNull
    public StateFlow<Integer> getExpiryWarningDays() { return expiryWarningDays; }

    @NonNull
    public StateFlow<ShoppingBudgetImpact> getBudgetImpact() { return budgetImpact; }

    public void setItemChecked(@NonNull ShoppingListItem item, boolean checked) {
        AndroidCoroutines.<Unit>launch(scope, next -> {
            if (!checked) {
                return shopping.setChecked(item, false, next);
            }
            double price = Math.max(1, item.getQuantityNeeded()) * item.getEstimatedUnitPrice();
            return completePurchase(item, price / purchasedInventoryQuantity(item), next);
        });
    }

    public void completeItemWithPurchasePrice(@NonNull ShoppingListItem item, double totalPricePaid) {
        AndroidCoroutines.<Unit>launch(scope, next ->
                completePurchase(item, totalPricePaid / purchasedInventoryQuantity(item), next));
    }

    @Nullable
    public Object addManualItem(@NonNull String name, @NonNull String category, int quantityNeeded,
            double estimatedUnitPrice, @NonNull String assignedTo, @NonNull Continuation<? super String> completion) {
        return AndroidCoroutines.<String>recoverCall(next -> AndroidCoroutines.<Unit, String>then(
                        write -> shopping.addManualItem(name, category, quantityNeeded, estimatedUnitPrice, assignedTo, write),
                        (unused, resumed) -> null, next),
                error -> error.getMessage() == null ? "Unable to add this shopping item." : error.getMessage(), completion);
    }

    public void assignItem(@NonNull ShoppingListItem item, @NonNull String assignee) {
        AndroidCoroutines.<Unit>launchWithMessage(scope, outgoingMessages, next -> shopping.assignTo(item, assignee, next),
                unused -> null, "Unable to change sharing scope.");
    }

    public void setMemberFilter(@NonNull ShoppingMemberFilter filter) {
        AndroidCoroutines.<Unit>launchWithMessage(scope, outgoingMessages,
                next -> sessions.selectScope(filter == ShoppingMemberFilter.HOUSEHOLD, next),
                unused -> null, "Unable to switch scope.");
    }

    @NonNull
    public String buildShareText() {
        Map<String, List<ShoppingListItem>> byAssignee = new TreeMap<>();
        for (ShoppingListItem item : shoppingListItems.getValue()) {
            if (!item.isChecked()) {
                byAssignee.computeIfAbsent(displayAssignee(item.getAssignedTo()), ignored -> new ArrayList<>()).add(item);
            }
        }
        if (byAssignee.isEmpty()) {
            return "Smart Grocery shopping list is clear.";
        }
        StringBuilder result = new StringBuilder("Smart Grocery Shopping List\n");
        for (Map.Entry<String, List<ShoppingListItem>> group : byAssignee.entrySet()) {
            result.append('\n').append(group.getKey()).append(":\n");
            for (ShoppingListItem item : group.getValue()) {
                double cost = item.getQuantityNeeded() * item.getEstimatedUnitPrice();
                result.append("- ").append(item.getItemName()).append(" x").append(item.getQuantityNeeded())
                        .append(" (").append(item.getCategory()).append(") ~ RM ").append(String.format("%.2f", cost)).append('\n');
            }
        }
        return StringsKt.trim(result).toString();
    }

    public void deleteItem(@NonNull ShoppingListItem item) {
        AndroidCoroutines.<Unit>launch(scope, next -> shopping.delete(item, next));
    }

    public void clearCheckedItems() {
        AndroidCoroutines.<Unit>launch(scope, shopping::deleteChecked);
    }

    public void increaseExpiryWarningDays() {
        preferences.setExpiryWarningDays(expiryWarningDays.getValue() + 1);
    }

    public void decreaseExpiryWarningDays() {
        preferences.setExpiryWarningDays(expiryWarningDays.getValue() - 1);
    }

    private Object completePurchase(ShoppingListItem item, double unitPrice, Continuation<? super Unit> completion) {
        return RoomDatabaseKt.<Unit>withTransaction(database, next ->
                AndroidCoroutines.<ShoppingListItem, Unit>then(
                        query -> database.shoppingListDao().getById(item.getId(), query),
                        (current, resumed) -> {
                            if (current == null) {
                                return Unit.INSTANCE;
                            }
                            if (!item.getUserId().equals(current.getUserId())
                                    || !Objects.equals(item.getHouseholdId(), current.getHouseholdId())) {
                                throw new IllegalArgumentException("Shopping scope changed; reload first.");
                            }
                            return AndroidCoroutines.<Unit, Unit>then(
                                    update -> addPurchasedInventory(item, Math.max(0.0, unitPrice), update),
                                    (unused, delete) -> shopping.delete(item, delete), resumed);
                        }, next), completion);
    }

    private Object addPurchasedInventory(ShoppingListItem item, double price, Continuation<? super Unit> completion) {
        Integer source = item.getSourceItemId();
        return AndroidCoroutines.<GroceryItem, Unit>then(
                query -> source == null ? null : groceries.getItemById(source, query),
                (inventory, next) -> {
                    int purchased = purchasedInventoryQuantity(item);
                    if (inventory != null) {
                        int total = Math.max(inventory.getQuantity() + purchased, inventory.getThreshold() + 1);
                        GroceryItem repriced = inventory.copy(inventory.getId(), inventory.getName(), inventory.getQuantity(),
                                price, inventory.getCategory(), inventory.getExpiryDate(), inventory.getThreshold(),
                                inventory.getPurchasedDate(), inventory.getSubcategory(), inventory.getUserId(),
                                inventory.getHouseholdId(), inventory.getAddedByName(), inventory.getLastModifiedByName(),
                                inventory.getLastModifiedAction(), inventory.getLastModifiedAt());
                        return AndroidCoroutines.<Unit, Unit>then(write -> groceries.update(repriced, write),
                                (unused, update) -> groceries.updateQuantity(inventory.getId(), total, update), next);
                    }
                    GroceryItem draft = new GroceryItem(0, item.getItemName(), purchased, price, item.getCategory(),
                            ExpiryEstimator.estimateExpiryTimestamp(item.getItemName(), item.getCategorySelection()),
                            GroceryCategories.defaultThreshold(item.getCategory()), System.currentTimeMillis(),
                            item.getSubcategory(), "", null, null, null, null, null);
                    return AndroidCoroutines.<Long, Unit>then(write -> groceries.insert(draft, write),
                            (id, unused) -> Unit.INSTANCE, next);
                }, completion);
    }

    private static ShoppingBudgetImpact budgetImpact(List<ShoppingListItem> items, BudgetEntry budget, Double spent) {
        double cartTotal = 0.0;
        for (ShoppingListItem item : items) {
            if (!item.isChecked()) {
                cartTotal += Math.max(1, item.getQuantityNeeded()) * Math.max(0.0, item.getEstimatedUnitPrice());
            }
        }
        double totalBudget = budget == null ? 0.0 : budget.getLimitAmount();
        double spentAmount = spent == null ? 0.0 : spent;
        return new ShoppingBudgetImpact(cartTotal, totalBudget, spentAmount, totalBudget - spentAmount);
    }

    private static String displayAssignee(String name) {
        return "Shared".equalsIgnoreCase(name) || "Housemate".equalsIgnoreCase(name) ? "Household" : name;
    }

    private static int purchasedInventoryQuantity(ShoppingListItem item) {
        boolean pantry = GroceryCategories.isFood(item.getCategory())
                && (isPantryText(item.getCategorySelection()) || isPantryText(item.getItemName()));
        return pantry ? 1 : Math.max(1, item.getQuantityNeeded());
    }

    private static boolean isPantryText(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String word : PANTRY_WORDS) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }
}

package com.xmum.smartgrocery.ui.inventory;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelKt;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.repository.GroceryRepository;
import com.xmum.smartgrocery.ui.common.NotificationHelper;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.Category;
import com.xmum.smartgrocery.utils.DateUtils;
import com.xmum.smartgrocery.utils.FoodSubcategory;
import com.xmum.smartgrocery.utils.GroceryCategories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.text.StringsKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

public final class InventoryViewModel extends AndroidViewModel {
    private static final Comparator<GroceryItem> BATCH_ORDER = Comparator
            .comparingLong((GroceryItem item) -> sortableExpiry(item.getExpiryDate()))
            .thenComparingInt(GroceryItem::getId);
    private static final Comparator<GroceryItem> CHANGE_ORDER = Comparator
            .comparingLong((GroceryItem item) -> item.getLastModifiedAt() == null ? Long.MIN_VALUE : item.getLastModifiedAt())
            .thenComparingInt(GroceryItem::getId);

    private final GroceryRepository repository;
    private final CoroutineScope scope;
    private final NotificationHelper notificationHelper;
    private final Set<Integer> alertedIds = new HashSet<>();
    private final MutableStateFlow<String> search = StateFlowKt.MutableStateFlow("");
    private final MutableStateFlow<InventoryFilter> filter = StateFlowKt.MutableStateFlow(InventoryFilter.ALL_IN_STOCK);
    private final MutableStateFlow<Category> category = StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<FoodSubcategory> subcategory = StateFlowKt.MutableStateFlow(null);
    private final StateFlow<String> searchQuery = FlowKt.asStateFlow(search);
    private final StateFlow<InventoryFilter> inventoryFilter = FlowKt.asStateFlow(filter);
    private final StateFlow<Category> categoryFilter = FlowKt.asStateFlow(category);
    private final StateFlow<FoodSubcategory> subcategoryFilter = FlowKt.asStateFlow(subcategory);
    private final StateFlow<Integer> expiryWarningDays;
    private final StateFlow<List<InventoryCardItem>> inventoryItems;

    public InventoryViewModel(@NonNull Application application) {
        super(application);
        SmartGroceryApp app = (SmartGroceryApp) application;
        repository = app.getGroceryRepository();
        expiryWarningDays = app.getPreferencesRepository().getExpiryWarningDays();
        notificationHelper = new NotificationHelper(application);
        scope = ViewModelKt.getViewModelScope(this);

        Flow<Pair<Category, FoodSubcategory>> selection = FlowKt.combine(category, subcategory,
                (main, leaf, ignored) -> new Pair<>(main, leaf));
        Flow<List<InventoryCardItem>> filtered = FlowKt.combine(search, filter, selection, expiryWarningDays,
                repository.getAllGroceryItems(), (query, group, categories, days, items, ignored) ->
                        cards(query, group, categories.getFirst(), categories.getSecond(), days, items));
        inventoryItems = FlowKt.stateIn(AndroidCoroutines.background(filtered), scope, SharingStarted.Companion.WhileSubscribed(5000L, Long.MAX_VALUE),
                Collections.emptyList());

        Flow<Pair<List<GroceryItem>, Integer>> alertSource = FlowKt.combine(repository.getAllGroceryItems(),
                expiryWarningDays, (items, days, ignored) -> new Pair<>(items, days));
        AndroidCoroutines.observe(scope, alertSource, values -> notifyExpiring(values.getFirst(), values.getSecond()));
    }

    @NonNull
    public StateFlow<String> getSearchQuery() { return searchQuery; }

    @NonNull
    public StateFlow<InventoryFilter> getInventoryFilter() { return inventoryFilter; }

    @NonNull
    public StateFlow<Category> getCategoryFilter() { return categoryFilter; }

    @NonNull
    public StateFlow<FoodSubcategory> getSubcategoryFilter() { return subcategoryFilter; }

    @NonNull
    public StateFlow<Integer> getExpiryWarningDays() { return expiryWarningDays; }

    @NonNull
    public StateFlow<List<InventoryCardItem>> getInventoryItems() { return inventoryItems; }

    public void setSearchQuery(@NonNull String query) {
        search.setValue(query);
    }

    public void setInventoryFilter(@NonNull InventoryFilter value) {
        filter.setValue(value);
    }

    public void setCategoryFilter(@Nullable Category value) {
        if (category.getValue() != value) {
            subcategory.setValue(null);
        }
        category.setValue(value);
    }

    public void setSubcategoryFilter(@Nullable FoodSubcategory value) {
        subcategory.setValue(category.getValue() == Category.FOOD_BEVERAGE ? value : null);
    }

    public void incrementQuantity(@NonNull InventoryCardItem card) {
        AndroidCoroutines.<Unit>launch(scope, next -> {
            if (card.getBatches().isEmpty()) {
                return Unit.INSTANCE;
            }
            return AndroidCoroutines.<GroceryItem, Unit>then(read ->
                    repository.getItemById(card.getBatches().get(0).getBatchId(), read),
                    (item, write) -> item == null ? Unit.INSTANCE
                            : repository.updateQuantity(item.getId(), item.getQuantity() + 1, write), next);
        });
    }

    public void decrementQuantity(@NonNull InventoryCardItem card) {
        AndroidCoroutines.<Unit>launch(scope, next -> repository.consumeCardQuantity(card.getItem().getName(),
                card.getItem().getUnit(), card.getItem().getStorageLocation(), 1, next));
    }

    public void deleteItem(@NonNull InventoryCardItem card) {
        AndroidCoroutines.<Unit>launch(scope, next -> AndroidCoroutines.forEachAsync(card.getBatches(),
                (batch, resumed) -> AndroidCoroutines.<GroceryItem, Unit>then(
                        read -> repository.getItemById(batch.getBatchId(), read),
                        (item, write) -> item == null ? Unit.INSTANCE : repository.delete(item, write), resumed), next));
    }

    public void clearExpiredItems() {
        AndroidCoroutines.<Integer>launch(scope, next -> repository.deleteExpiredItems(DateUtils.getStartOfToday(), next));
    }

    public void addNewItem(@NonNull String name, int quantity, double price, @NonNull String category,
            @Nullable Long expiryDate, int threshold) {
        AndroidCoroutines.<Long>launch(scope, next -> repository.insert(new GroceryItem(0, name, quantity, price,
                category, expiryDate, threshold, System.currentTimeMillis(), null, "", null, null, null, null, null), next));
    }

    public void updateExistingItem(@NonNull GroceryItem item) {
        AndroidCoroutines.<Unit>launch(scope, next -> repository.update(item, next));
    }

    private void notifyExpiring(List<GroceryItem> items, int warningDays) {
        for (GroceryItem item : items) {
            if (item.getQuantity() > 0 && DateUtils.isExpiringWithin(item.getExpiryDate(), warningDays)
                    && alertedIds.add(item.getId())) {
                notificationHelper.sendExpiringSoonNotification(item.getName(), DateUtils.daysUntil(item.getExpiryDate()));
            }
        }
    }

    private static List<InventoryCardItem> cards(String query, InventoryFilter filter, Category category,
            FoodSubcategory subcategory, int warningDays, List<GroceryItem> inventory) {
        Map<Pair<String, String>, GroceryItem> latestChanges = new HashMap<>();
        Map<String, List<GroceryItem>> groups = new LinkedHashMap<>();
        for (GroceryItem item : inventory) {
            String name = normalizedName(item);
            if (item.getHouseholdId() != null) {
                Pair<String, String> key = new Pair<>(item.getHouseholdId(), name);
                GroceryItem latest = latestChanges.get(key);
                if (latest == null || CHANGE_ORDER.compare(item, latest) > 0) {
                    latestChanges.put(key, item);
                }
            }
            if (matches(item, query, filter, category, subcategory, warningDays)) {
                groups.computeIfAbsent(name, ignored -> new ArrayList<>()).add(item);
            }
        }

        List<InventoryCardItem> cards = new ArrayList<>(groups.size());
        for (List<GroceryItem> batches : groups.values()) {
            batches.sort(BATCH_ORDER);
            GroceryItem first = batches.get(0);
            GroceryItem latest = latestChanges.get(new Pair<>(first.getHouseholdId(), normalizedName(first)));
            int quantity = 0;
            List<InventoryBatch> cardBatches = new ArrayList<>(batches.size());
            for (GroceryItem item : batches) {
                quantity += item.getQuantity();
                cardBatches.add(new InventoryBatch(item.getId(), item.getQuantity(), item.getExpiryDate(), item.getPrice()));
            }
            cards.add(new InventoryCardItem(first, quantity, first.getExpiryDate(), cardBatches,
                    latest == null ? null : latest.getSharedChangeIndicator()));
        }
        cards.sort(Comparator.comparingLong(card -> sortableExpiry(card.getEarliestExpiryDate())));
        return cards;
    }

    private static boolean matches(GroceryItem item, String query, InventoryFilter filter, Category category,
            FoodSubcategory subcategory, int warningDays) {
        if (item.getQuantity() <= 0 || (category != null && GroceryCategories.mainCategory(item.getCategory()) != category)
                || (category == Category.FOOD_BEVERAGE && subcategory != null
                && !subcategory.getLabel().equals(GroceryCategories.subcategory(item.getCategory(), item.getSubcategory())))) {
            return false;
        }
        if (!StringsKt.isBlank(query) && !StringsKt.contains(item.getName(), query, true)) {
            return false;
        }
        switch (filter) {
            case EXPIRING_SOON:
                return DateUtils.isExpiringWithin(item.getExpiryDate(), warningDays);
            case EXPIRED:
                return DateUtils.isExpired(item.getExpiryDate());
            default:
                return !DateUtils.isExpired(item.getExpiryDate());
        }
    }

    private static String normalizedName(GroceryItem item) {
        return StringsKt.trim(item.getName()).toString().toLowerCase(Locale.ROOT)
                + "\0" + item.getUnit() + "\0" + item.getStorageLocation();
    }

    private static long sortableExpiry(Long date) {
        return date == null ? Long.MAX_VALUE : date;
    }
}

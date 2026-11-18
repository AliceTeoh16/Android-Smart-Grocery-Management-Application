package com.xmum.smartgrocery.ui.inventory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.ui.common.NotificationHelper
import com.xmum.smartgrocery.utils.DateUtils
import com.xmum.smartgrocery.utils.Category
import com.xmum.smartgrocery.utils.GroceryCategories
import com.xmum.smartgrocery.utils.FoodSubcategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class InventoryFilter {
    ALL_IN_STOCK,
    EXPIRING_SOON,
    EXPIRED
}

class InventoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartGroceryApp
    private val repository = app.groceryRepository
    private val preferencesRepository = app.preferencesRepository
    private val notificationHelper = NotificationHelper(application)
    private val notifiedExpiringItemIds = mutableSetOf<Int>()

    private val _searchQuery = MutableStateFlow("")
    private val _inventoryFilter = MutableStateFlow(InventoryFilter.ALL_IN_STOCK)
    private val _categoryFilter = MutableStateFlow<Category?>(null)
    private val _subcategoryFilter = MutableStateFlow<FoodSubcategory?>(null)
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val inventoryFilter: StateFlow<InventoryFilter> = _inventoryFilter.asStateFlow()
    val categoryFilter: StateFlow<Category?> = _categoryFilter.asStateFlow()
    val subcategoryFilter: StateFlow<FoodSubcategory?> = _subcategoryFilter.asStateFlow()
    val expiryWarningDays: StateFlow<Int> = preferencesRepository.expiryWarningDays

    // Combining search query and all items from database to perform real-time UI filtering
    @OptIn(ExperimentalCoroutinesApi::class)
    val inventoryItems: StateFlow<List<InventoryCardItem>> = combine(
        _searchQuery,
        _inventoryFilter,
        combine(_categoryFilter, _subcategoryFilter) { category, subcategory -> category to subcategory },
        expiryWarningDays,
        repository.allGroceryItems
    ) { query, filter, selection, warningDays, items ->
        val (category, subcategory) = selection
        val latestSharedChanges = items.filter { it.householdId != null }
            .groupBy { it.householdId to it.name.trim().lowercase() }
            .mapValues { (_, batches) -> batches.maxWithOrNull(
                compareBy<GroceryItem> { it.lastModifiedAt ?: Long.MIN_VALUE }.thenBy { it.id })?.sharedChangeIndicator }
        items
            .filter { category == null || GroceryCategories.mainCategory(it.category) == category }
            .filter { category != Category.FOOD_BEVERAGE || subcategory == null ||
                GroceryCategories.subcategory(it.category, it.subcategory) == subcategory.label }
            .filter { item ->
                item.quantity > 0 && when (filter) {
                    InventoryFilter.ALL_IN_STOCK -> !DateUtils.isExpired(item.expiryDate)
                    InventoryFilter.EXPIRING_SOON -> DateUtils.isExpiringWithin(
                        item.expiryDate,
                        warningDays
                    )
                    InventoryFilter.EXPIRED -> DateUtils.isExpired(item.expiryDate)
                }
            }
            .filter { it.name.contains(query, ignoreCase = true) || query.isBlank() }
            .groupBy { it.name.trim().lowercase() }
            .values
            .map { batches ->
                val sortedBatches = batches.sortedWith(compareBy<GroceryItem> { it.expiryDate ?: Long.MAX_VALUE }.thenBy { it.id })
                InventoryCardItem(
                    item = sortedBatches.first(),
                    changeIndicator = latestSharedChanges[sortedBatches.first().let { it.householdId to it.name.trim().lowercase() }],
                    totalQuantity = sortedBatches.sumOf { it.quantity },
                    earliestExpiryDate = sortedBatches.first().expiryDate,
                    batches = sortedBatches.map {
                        InventoryBatch(
                            batchId = it.id,
                            quantity = it.quantity,
                            expiryDate = it.expiryDate,
                            price = it.price
                        )
                    }
                )
            }
            .sortedBy { it.earliestExpiryDate ?: Long.MAX_VALUE }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            combine(repository.allGroceryItems, expiryWarningDays) { items, warningDays ->
                items to warningDays
            }.collect { (items, warningDays) ->
                items
                    .filter {
                        it.quantity > 0 &&
                        DateUtils.isExpiringWithin(it.expiryDate, warningDays) &&
                            notifiedExpiringItemIds.add(it.id)
                    }
                    .forEach { item ->
                        val daysLeft = DateUtils.daysUntil(item.expiryDate)
                        notificationHelper.sendExpiringSoonNotification(item.name, daysLeft)
                    }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setInventoryFilter(filter: InventoryFilter) {
        _inventoryFilter.value = filter
    }

    fun setCategoryFilter(category: Category?) {
        if (_categoryFilter.value != category) _subcategoryFilter.value = null
        _categoryFilter.value = category
    }

    fun setSubcategoryFilter(subcategory: FoodSubcategory?) {
        _subcategoryFilter.value = subcategory.takeIf { _categoryFilter.value == Category.FOOD_BEVERAGE }
    }

    fun incrementQuantity(card: InventoryCardItem) {
        viewModelScope.launch {
            val batch = card.batches.firstOrNull() ?: return@launch
            val item = repository.getItemById(batch.batchId) ?: return@launch
            repository.updateQuantity(item.id, item.quantity + 1)
        }
    }

    fun decrementQuantity(card: InventoryCardItem) {
        viewModelScope.launch {
            repository.consumeQuantityFefo(card.item.name, 1)
            // RestockMonitor observes the committed stock and offers a review notification.
        }
    }

    fun deleteItem(card: InventoryCardItem) {
        viewModelScope.launch {
            card.batches.forEach { batch ->
                repository.getItemById(batch.batchId)?.let { repository.delete(it) }
            }
        }
    }

    fun clearExpiredItems() {
        viewModelScope.launch {
            repository.deleteExpiredItems(DateUtils.getStartOfToday())
        }
    }

    fun addNewItem(name: String, quantity: Int, price: Double, category: String, expiryDate: Long?, threshold: Int) {
        viewModelScope.launch {
            val newItem = GroceryItem(
                name = name,
                quantity = quantity,
                price = price,
                category = category,
                expiryDate = expiryDate,
                threshold = threshold
            )
            repository.insert(newItem)
        }
    }

    fun updateExistingItem(item: GroceryItem) {
        viewModelScope.launch {
            repository.update(item)
        }
    }
}

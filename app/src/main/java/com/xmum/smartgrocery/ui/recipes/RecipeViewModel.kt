package com.xmum.smartgrocery.ui.recipes

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.utils.ChecklistShoppingMerge
import com.xmum.smartgrocery.data.repository.RecipeRepository
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

data class ChecklistUiState(
    val availableIngredients: List<GroceryItem> = emptyList(),
    val expiryWarningDays: Int = 3,
    val userPrompt: String = "",
    val searchHistory: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasGenerated: Boolean = false,
    val generationError: String? = null,
    val checklistResult: RecipeRepository.GroceryChecklistResult? = null,
    val selectableMissingItems: List<SelectableChecklistItem> = emptyList(),
    val selectedMissingCount: Int = 0,
    val activeChecklistStatus: String? = null,
    val savedChecklists: List<SavedChecklist> = emptyList(),
    val selectableInventoryItems: List<SelectableInventoryChecklistItem> = emptyList(),
    val isClearingHistory: Boolean = false,
    val isAddingToShopping: Boolean = false
) {
    val selectedShoppingItems: List<RecipeRepository.MissingChecklistItem> get() =
        selectableMissingItems.filter { it.isSelected }.map { it.checklistItem } +
            selectableInventoryItems.filter { it.isSelected }.map { it.inventoryItem.asShoppingItem(availableIngredients) }
    val selectedShoppingCount: Int get() = selectedShoppingItems.size
    val hasSelectedItemsInInventory: Boolean get() = selectedShoppingItems.any { selected ->
        availableIngredients.any { stock -> stock.quantity > 0 && sameChecklistFood(stock.name, selected.item) }
    }
}

data class SelectableChecklistItem(
    val checklistItem: RecipeRepository.MissingChecklistItem,
    val isSelected: Boolean = true,
    val isAddedToShopping: Boolean = false,
    val isEditable: Boolean = true
)

data class SavedChecklist(
    val id: String,
    val dishName: String,
    val servings: Int,
    val status: String,
    val createdAt: Long,
    val missingChecklist: List<RecipeRepository.MissingChecklistItem>,
    val inventoryDeducted: List<RecipeRepository.DeductedInventoryItem>,
    val addedShoppingItemKeys: List<String>? = emptyList(),
    // The complete generation and user choices are stored together in Room's stateJson.
    val ingredients: List<RecipeRepository.ChecklistIngredient>? = emptyList(),
    val steps: List<String>? = emptyList(),
    val uncheckedInventoryItemKeys: List<String>? = emptyList(),
    val uncheckedMissingItemKeys: List<String>? = emptyList(),
    val userPrompt: String? = null
) {
    fun withCookingDetailsFrom(cached: SavedChecklist?): SavedChecklist {
        if (cached == null || id != cached.id || createdAt != cached.createdAt || dishName != cached.dishName || servings != cached.servings ||
            missingChecklist != cached.missingChecklist || inventoryDeducted != cached.inventoryDeducted) return this
        return copy(ingredients = cached.ingredients.orEmpty(), steps = cached.steps.orEmpty(),
            uncheckedInventoryItemKeys = cached.uncheckedInventoryItemKeys.orEmpty(),
            uncheckedMissingItemKeys = cached.uncheckedMissingItemKeys.orEmpty(), userPrompt = cached.userPrompt)
    }
}

class RecipeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartGroceryApp
    private val groceryRepository = app.groceryRepository
    internal var recipeRepository = app.recipeRepository
    private val shoppingListRepository = app.shoppingListRepository
    private val preferencesRepository = app.preferencesRepository
    private val checklistHistoryRepository = app.checklistHistoryRepository
    private val historyPrefs = application.getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
    private val checklistPrefs = application.getSharedPreferences(CHECKLIST_PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyOperations = Mutex()
    private val historyReady = CompletableDeferred<Unit>()
    private var activeSnapshot: SavedChecklist? = null
    private var generationJob: Job? = null
    private var requestVersion = 0L

    private val activeInventory: StateFlow<List<GroceryItem>> = combine(
        groceryRepository.allGroceryItems,
        preferencesRepository.expiryWarningDays
    ) { items, warningDays ->
        items
            .filter { it.quantity > 0 && com.xmum.smartgrocery.utils.GroceryCategories.isFood(it.category) && !DateUtils.isExpired(it.expiryDate) }
            .sortedWith(
                compareByDescending<GroceryItem> { DateUtils.isExpiringWithin(it.expiryDate, warningDays) }
                    .thenBy { it.expiryDate ?: Long.MAX_VALUE }
            )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val userPrompt = MutableStateFlow("")
    private val searchHistory = MutableStateFlow(loadSearchHistory())
    private val isLoading = MutableStateFlow(false)
    private val hasGenerated = MutableStateFlow(false)
    private val generationError = MutableStateFlow<String?>(null)
    private val checklistResult = MutableStateFlow<RecipeRepository.GroceryChecklistResult?>(null)
    private val selectableMissingItems = MutableStateFlow<List<SelectableChecklistItem>>(emptyList())
    private val selectableInventoryItems = MutableStateFlow<List<SelectableInventoryChecklistItem>>(emptyList())
    private val activeChecklistId = MutableStateFlow<String?>(null)
    private val activeChecklistStatus = MutableStateFlow<String?>(null)
    private val savedChecklists = MutableStateFlow<List<SavedChecklist>>(emptyList())
    private val isClearingHistory = MutableStateFlow(false)
    private val isAddingToShopping = MutableStateFlow(false)
    private val _shoppingAddEvents = MutableSharedFlow<String>()

    val shoppingAddEvents: SharedFlow<String> = _shoppingAddEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                historyOperations.withLock {
                    val rows = checklistHistoryRepository.getAllHistorySync()
                    val legacy = if (rows.any { it.stateJson.isBlank() } ||
                        (rows.isEmpty() && !checklistPrefs.getBoolean(KEY_ROOM_HISTORY_MIGRATED, false))) {
                        loadSavedChecklists().associateBy { it.id }
                    } else emptyMap()
                    if (rows.isEmpty() && !checklistPrefs.getBoolean(KEY_ROOM_HISTORY_MIGRATED, false)) {
                        legacy.values.sortedBy { it.createdAt }.forEach {
                            checklistHistoryRepository.saveChecklistWithPruning(ChecklistHistorySnapshot.encode(it))
                        }
                    } else {
                        rows.filter { it.stateJson.isBlank() }.forEach { row ->
                            val migrated = ChecklistHistorySnapshot.decode(row, legacy[row.id])
                            checklistHistoryRepository.backfillState(ChecklistHistorySnapshot.encode(migrated))
                        }
                    }
                    checklistHistoryRepository.pruneToLatest()
                    savedChecklists.value = checklistHistoryRepository.getAllHistorySync().map { ChecklistHistorySnapshot.decode(it) }
                    // Retire duplicate preference payloads only after successful Room persistence/backfill.
                    checklistPrefs.edit().putBoolean(KEY_ROOM_HISTORY_MIGRATED, true).remove(KEY_SAVED_CHECKLISTS).apply()
                    if (requestVersion == 0L) {
                        val activeId = checklistPrefs.getString(KEY_ACTIVE_HISTORY_ID, null)
                        (savedChecklists.value.firstOrNull { it.id == activeId }
                            ?: savedChecklists.value.firstOrNull { it.status == STATUS_PENDING })?.let(::hydrateChecklist)
                    }
                }
                historyReady.complete(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                historyReady.completeExceptionally(error)
                generationError.value = "Couldn’t load checklist history. Reopen this screen to retry."
            }
            checklistHistoryRepository.allHistory.collect { items ->
                savedChecklists.value = items.map { ChecklistHistorySnapshot.decode(it) }
            }
        }
    }

    val uiState: StateFlow<ChecklistUiState> = combine(
        activeInventory,
        preferencesRepository.expiryWarningDays,
        userPrompt,
        searchHistory,
        isLoading,
        hasGenerated,
        generationError,
        checklistResult,
        selectableMissingItems,
        activeChecklistStatus,
        savedChecklists,
        selectableInventoryItems,
        isClearingHistory,
        shoppingListRepository.shoppingListItems,
        isAddingToShopping
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val pendingNames = ChecklistShoppingMerge.pendingNames(values[13] as List<ShoppingListItem>)
        @Suppress("UNCHECKED_CAST")
        val selectableItems = (values[8] as List<SelectableChecklistItem>).map {
            it.copy(isAddedToShopping = ChecklistShoppingMerge.isPending(it.checklistItem.item, pendingNames),
                isEditable = values[9] == STATUS_PENDING && values[4] == false && values[14] == false)
        }
        @Suppress("UNCHECKED_CAST")
        val ownedItems = (values[11] as List<SelectableInventoryChecklistItem>).map {
            it.copy(isAddedToShopping = ChecklistShoppingMerge.isPending(it.inventoryItem.item, pendingNames))
        }
        @Suppress("UNCHECKED_CAST")
        val checklists = values[10] as List<SavedChecklist>
        @Suppress("UNCHECKED_CAST")
        ChecklistUiState(
            availableIngredients = values[0] as List<GroceryItem>,
            expiryWarningDays = values[1] as Int,
            userPrompt = values[2] as String,
            searchHistory = values[3] as List<String>,
            isLoading = values[4] as Boolean,
            hasGenerated = values[5] as Boolean,
            generationError = values[6] as String?,
            checklistResult = values[7] as RecipeRepository.GroceryChecklistResult?,
            selectableMissingItems = selectableItems,
            selectedMissingCount = selectableItems.count { it.isSelected },
            activeChecklistStatus = values[9] as String?,
            savedChecklists = checklists,
            selectableInventoryItems = ownedItems,
            isClearingHistory = values[12] as Boolean,
            isAddingToShopping = values[14] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChecklistUiState()
    )

    fun setPrompt(prompt: String) {
        userPrompt.value = prompt
    }

    fun generateChecklist(promptOverride: String? = null) {
        if (isClearingHistory.value) return
        val version = ++requestVersion
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            val prompt = promptOverride?.takeIf { it.isNotBlank() } ?: userPrompt.value.trim()
            userPrompt.value = prompt

            if (prompt.isBlank()) {
                isLoading.value = false
                checklistResult.value = null
                selectableMissingItems.value = emptyList()
                selectableInventoryItems.value = emptyList()
                generationError.value = "Enter a dish request first, for example: Tomato Beef Noodles for 4 people."
                hasGenerated.value = true
                return@launch
            }

            isLoading.value = true
            generationError.value = null

            try {
                delay(250)
                val result = recipeRepository.generateChecklistAssistantResult(
                    inventory = activeInventory.value,
                    userPrompt = prompt,
                    expiryWarningDays = preferencesRepository.expiryWarningDays.value
                )
                historyReady.await()
                val timestamp = System.currentTimeMillis()
                val saved = SavedChecklist(ChecklistHistorySnapshot.newId(timestamp), result.dishName, result.servings,
                    STATUS_PENDING, timestamp, result.missingChecklist, result.inventoryDeducted,
                    ingredients = result.ingredients, steps = result.steps, userPrompt = prompt)
                historyOperations.withLock {
                    checklistHistoryRepository.saveChecklistWithPruning(ChecklistHistorySnapshot.encode(saved))
                    if (version == requestVersion) hydrateChecklist(saved)
                }
                saveSuccessfulSearch(prompt)
                generationError.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (version != requestVersion) return@launch
                checklistResult.value = null
                selectableMissingItems.value = emptyList()
                selectableInventoryItems.value = emptyList()
                generationError.value = error.message ?: "AI checklist generation failed."
            } finally {
                if (version == requestVersion) {
                    hasGenerated.value = true
                    isLoading.value = false
                }
            }
        }
    }

    fun setChecklistItemSelected(item: RecipeRepository.MissingChecklistItem, selected: Boolean) {
        if (activeChecklistStatus.value != STATUS_PENDING || isLoading.value || isAddingToShopping.value) return
        selectableMissingItems.value = selectableMissingItems.value.map { selectable ->
            if (selectable.checklistItem.selectionKey() == item.selectionKey()) {
                selectable.copy(isSelected = selected)
            } else {
                selectable
            }
        }
        updateActiveSnapshot { it.copy(uncheckedMissingItemKeys = selectableMissingItems.value.filterNot { row -> row.isSelected }
            .map { row -> row.checklistItem.selectionKey() }) }
    }

    fun setInventoryItemSelected(item: RecipeRepository.DeductedInventoryItem, selected: Boolean) {
        if (activeChecklistStatus.value != STATUS_PENDING || isLoading.value || isAddingToShopping.value) return
        val key = item.inventorySelectionKey()
        selectableInventoryItems.value = selectableInventoryItems.value.map {
            if (it.inventoryItem.inventorySelectionKey() == key) it.copy(isSelected = selected) else it
        }
        val unchecked = selectableInventoryItems.value.filterNot { it.isSelected }.map { it.inventoryItem.inventorySelectionKey() }
        updateActiveSnapshot { it.copy(uncheckedInventoryItemKeys = unchecked) }
    }

    private fun restoreInventorySelection(checklist: SavedChecklist) {
        selectableInventoryItems.value = checklist.inventoryDeducted.map {
            SelectableInventoryChecklistItem(it, it.inventorySelectionKey() !in checklist.uncheckedInventoryItemKeys.orEmpty())
        }
    }

    fun addSelectedMissingItemsToShoppingList(assignedTo: String) {
        if (isAddingToShopping.value || isClearingHistory.value) return
        val source = activeSnapshot?.takeIf { it.status == STATUS_PENDING && !isLoading.value } ?: return
        val selectedMissing = selectableMissingItems.value.filter { it.isSelected }.map { it.checklistItem }
        val selectedInventory = selectableInventoryItems.value.filter { it.isSelected }.map { it.inventoryItem }
        val selectedItems = selectedMissing + selectedInventory.map { it.asShoppingItem(activeInventory.value) }
        if (selectedItems.isEmpty()) {
            viewModelScope.launch { _shoppingAddEvents.emit("No items selected.") }
            return
        }
        isAddingToShopping.value = true
        viewModelScope.launch {
            try {
                shoppingListRepository.addAiChecklistItems(selectedItems, assignedTo)
                val message = try {
                    markItemsAddedToShopping(source, selectedMissing, selectedInventory)
                    "${selectedItems.size} item(s) added to ${assignedTo.displayShoppingTarget()}!"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { "Items were added, but checklist choices couldn’t be saved." }
                _shoppingAddEvents.emit(message)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _shoppingAddEvents.emit(error.message ?: "Couldn’t add the selected items. Please try again.")
            } finally { isAddingToShopping.value = false }
        }
    }

    fun markActiveChecklistCooked() {
        val currentId = activeChecklistId.value ?: return
        markChecklistCookedById(currentId)
    }

    fun markChecklistCookedById(checklistId: String) {
        viewModelScope.launch {
            historyReady.await()
            val current = activeSnapshot?.takeIf { it.id == checklistId }
                ?: checklistHistoryRepository.getById(checklistId)?.let { ChecklistHistorySnapshot.decode(it) }
                ?: return@launch

            if (current.status != STATUS_PENDING) return@launch
            val selectedInventory = current.inventoryDeducted.filter {
                it.inventorySelectionKey() !in current.uncheckedInventoryItemKeys.orEmpty()
            }
            selectedInventory.forEach { deducted ->
                val item = groceryRepository.findBestMatchingItem(deducted.item.cleanInventoryName())
                    ?: groceryRepository.findBestMatchingItem(deducted.item)
                val quantityUsed = if (item != null && item.isPantryCookingItem(deducted.item)) {
                    estimatedPantryDeduction(item, deducted.item)
                } else {
                    deducted.usedQuantity.toInventoryQuantityInt()
                }
                if (item != null && quantityUsed > 0) {
                    groceryRepository.consumeQuantityFefo(item.name, quantityUsed)
                }
            }

            val updatedChecklist = current.copy(status = STATUS_COOKED)
            savedChecklists.value = savedChecklists.value.map {
                if (it.id == checklistId) updatedChecklist else it
            }
            persistSnapshot(updatedChecklist, STATUS_COOKED)
            if (activeChecklistId.value == checklistId) {
                clearActiveChecklist()
            }
            _shoppingAddEvents.emit(if (selectedInventory.isEmpty()) "Meal marked as cooked. No inventory items selected for deduction."
                else "Meal marked as cooked! Inventory updated.")
        }
    }

    fun cancelChecklistById(checklistId: String) {
        viewModelScope.launch {
            historyReady.await()
            val current = activeSnapshot?.takeIf { it.id == checklistId }
                ?: checklistHistoryRepository.getById(checklistId)?.let { ChecklistHistorySnapshot.decode(it) }
                ?: return@launch

            val updatedChecklist = current.copy(status = STATUS_CANCELLED)
            savedChecklists.value = savedChecklists.value.map {
                if (it.id == checklistId) updatedChecklist else it
            }
            persistSnapshot(updatedChecklist, STATUS_CANCELLED)
            if (activeChecklistId.value == checklistId) {
                clearActiveChecklist()
            }
            _shoppingAddEvents.emit("Recipe marked as cancelled. No inventory deducted.")
        }
    }

    /** Read by identity from Room. A restored generation never invokes the AI repository. */
    fun restoreChecklistById(id: String, onRestored: () -> Unit = {}) {
        if (isClearingHistory.value) return
        val version = ++requestVersion
        generationJob?.cancel()
        isLoading.value = false
        viewModelScope.launch {
            try {
                historyReady.await()
                historyOperations.withLock {
                    val row = checklistHistoryRepository.getById(id)
                    if (version != requestVersion) return@withLock
                    if (row == null) {
                        _shoppingAddEvents.emit("This checklist is no longer in recent history.")
                    } else {
                        hydrateChecklist(ChecklistHistorySnapshot.decode(row))
                        onRestored()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _shoppingAddEvents.emit("Couldn’t reopen this checklist. Please try again.")
            }
        }
    }

    fun showSavedChecklist(checklist: SavedChecklist) = restoreChecklistById(checklist.id)

    /** Called only after the history confirmation. Updates cannot recreate deleted rows. */
    fun clearAllChecklistHistory() {
        if (isClearingHistory.value) return
        isClearingHistory.value = true
        ++requestVersion
        generationJob?.cancel()
        isLoading.value = false
        viewModelScope.launch {
            try {
                historyReady.await()
                historyOperations.withLock {
                    checklistHistoryRepository.clearAll()
                    savedChecklists.value = emptyList()
                    clearActiveChecklist()
                    checklistPrefs.edit().putBoolean(KEY_ROOM_HISTORY_MIGRATED, true)
                        .remove(KEY_SAVED_CHECKLISTS).apply()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _shoppingAddEvents.emit("Couldn’t clear checklist history. Please try again.")
            } finally {
                isClearingHistory.value = false
            }
        }
    }

    private fun hydrateChecklist(checklist: SavedChecklist) {
        activeSnapshot = checklist
        activeChecklistId.value = checklist.id
        activeChecklistStatus.value = checklist.status
        checklistResult.value = checklist.toResult()
        selectableMissingItems.value = checklist.missingChecklist.map {
            SelectableChecklistItem(it,
                isSelected = it.selectionKey() !in checklist.uncheckedMissingItemKeys.orEmpty(),
                isAddedToShopping = false, isEditable = checklist.status == STATUS_PENDING)
        }
        restoreInventorySelection(checklist)
        generationError.value = null
        hasGenerated.value = true
        isLoading.value = false
        userPrompt.value = checklist.userPrompt ?: checklist.dishName
        checklistPrefs.edit().putString(KEY_ACTIVE_HISTORY_ID, checklist.id).apply()
    }

    private fun updateActiveSnapshot(change: (SavedChecklist) -> SavedChecklist) {
        val snapshot = change(activeSnapshot ?: return)
        activeSnapshot = snapshot
        savedChecklists.value = savedChecklists.value.map { if (it.id == snapshot.id) snapshot else it }
        viewModelScope.launch {
            try { persistSnapshot(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _shoppingAddEvents.emit("Couldn’t save checklist choices. Please try again.") }
        }
    }

    private suspend fun persistSnapshot(checklist: SavedChecklist, status: String? = null) {
        historyReady.await()
        historyOperations.withLock {
            checklistHistoryRepository.updateState(ChecklistHistorySnapshot.encode(checklist), status)
        }
    }

    private fun clearActiveChecklist() {
        activeSnapshot = null
        checklistPrefs.edit().remove(KEY_ACTIVE_HISTORY_ID).apply()
        activeChecklistId.value = null
        activeChecklistStatus.value = null
        checklistResult.value = null
        selectableMissingItems.value = emptyList()
        selectableInventoryItems.value = emptyList()
        generationError.value = null
        hasGenerated.value = false
        userPrompt.value = ""
    }

    private suspend fun markItemsAddedToShopping(source: SavedChecklist, items: List<RecipeRepository.MissingChecklistItem>,
        inventoryItems: List<RecipeRepository.DeductedInventoryItem> = emptyList()) {
        val addedKeys = (items.map { it.selectionKey() } + inventoryItems.map { it.inventorySelectionKey() }).toSet()
        val isActive = activeChecklistId.value == source.id
        val current = activeSnapshot?.takeIf { isActive } ?: source
        val updated = current.copy(addedShoppingItemKeys = (current.addedShoppingItemKeys.orEmpty() + addedKeys).distinct(),
            uncheckedMissingItemKeys = (current.uncheckedMissingItemKeys.orEmpty() + items.map { it.selectionKey() }).distinct())
        if (isActive) {
            selectableMissingItems.value = selectableMissingItems.value.map { selectable ->
                if (selectable.checklistItem.selectionKey() in addedKeys) {
                    selectable.copy(isSelected = false)
                } else {
                    selectable
                }
            }
            // Keep consumption checkboxes unchanged; live Room rows supply the shopping badge.
            activeSnapshot = updated
        }
        savedChecklists.value = savedChecklists.value.map { if (it.id == source.id) updated else it }
        persistSnapshot(updated)
    }

    private fun SavedChecklist.toResult(): RecipeRepository.GroceryChecklistResult {
        return RecipeRepository.GroceryChecklistResult(
            dishName = dishName,
            servings = servings,
            missingChecklist = missingChecklist,
            alreadyHave = inventoryDeducted.map { "${it.item} ${it.usedQuantity}" },
            inventoryDeducted = inventoryDeducted,
            ingredients = ingredients.orEmpty(),
            steps = steps.orEmpty()
        )
    }

    private fun loadSavedChecklists(): List<SavedChecklist> {
        val raw = checklistPrefs.getString(KEY_SAVED_CHECKLISTS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(raw, Array<SavedChecklist>::class.java)
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun saveSuccessfulSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return

        val updated = (listOf(normalized) + searchHistory.value)
            .distinctBy { it.lowercase() }
            .take(MAX_SEARCH_HISTORY)

        searchHistory.value = updated
        historyPrefs.edit()
            .putString(KEY_SEARCH_HISTORY, updated.joinToString(HISTORY_SEPARATOR))
            .apply()
    }

    private fun loadSearchHistory(): List<String> {
        return historyPrefs.getString(KEY_SEARCH_HISTORY, "")
            .orEmpty()
            .split(HISTORY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_SEARCH_HISTORY)
    }

    private fun RecipeRepository.MissingChecklistItem.selectionKey(): String {
        return "${item.trim().lowercase()}|${neededQuantity.trim().lowercase()}|${category.trim().lowercase()}"
    }

    private fun String.toShoppingQuantityInt(): Int {
        val firstNumber = Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
        return firstNumber?.let { ceil(it).toInt().coerceAtLeast(1) } ?: 1
    }

    private fun String.toInventoryQuantityInt(): Int {
        val normalized = lowercase()
        val firstNumber = Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull() ?: return 1
        return if (
            normalized.contains("g") ||
            normalized.contains("ml") ||
            normalized.contains("tbsp") ||
            normalized.contains("tsp")
        ) {
            1
        } else {
            ceil(firstNumber).toInt().coerceAtLeast(1)
        }
    }

    private fun String.cleanInventoryName(): String {
        return replace(Regex("""\([^)]*\)"""), "")
            .trim()
    }

    private fun GroceryItem.isPantryCookingItem(deductedItemName: String): Boolean {
        return category.isPantryLike() || name.isPantryLike() || deductedItemName.isPantryLike()
    }

    /**
     * Inventory stores stock as whole integer units, so pantry usage is represented
     * as one estimated stock unit instead of subtracting exact recipe measurements.
     * Liquid items represent roughly 1 tbsp / 10 ml per dish; dry seasonings
     * represent roughly 1 tsp per dish. Both map to one stored unit.
     */
    private fun estimatedPantryDeduction(item: GroceryItem, deductedItem: String): Int {
        val isLiquid = listOf(item.category, item.name, deductedItem)
            .any { it.isLiquidPantryName() }
        return if (isLiquid) {
            ESTIMATED_LIQUID_PANTRY_UNITS
        } else {
            ESTIMATED_DRY_PANTRY_UNITS
        }
    }

    private fun String.isPantryLike(): Boolean {
        val normalized = lowercase()
        return listOf(
            "pantry",
            "seasoning",
            "seasonings",
            "spice",
            "spices",
            "sauce",
            "soy sauce",
            "salt",
            "sugar",
            "pepper",
            "oil",
            "vinegar",
            "flour",
            "cornstarch",
            "starch"
        ).any { normalized.contains(it) }
    }

    private fun String.isLiquidPantryName(): Boolean {
        val normalized = lowercase()
        return listOf(
            "sauce",
            "soy sauce",
            "oil",
            "vinegar"
        ).any { normalized.contains(it) }
    }

    private fun String.displayShoppingTarget(): String {
        return if (equals("Me", ignoreCase = true)) {
            "your Personal Shopping List"
        } else {
            "the Household Shopping List"
        }
    }

    companion object {
        private const val ESTIMATED_LIQUID_PANTRY_UNITS = 1
        private const val ESTIMATED_DRY_PANTRY_UNITS = 1
        private const val SEARCH_HISTORY_PREFS = "ai_checklist_history"
        private const val CHECKLIST_PREFS = "ai_checklist_store"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_SAVED_CHECKLISTS = "saved_checklists"
        private const val HISTORY_SEPARATOR = "\u001F"
        private const val MAX_SEARCH_HISTORY = 8
        private const val KEY_ROOM_HISTORY_MIGRATED = "room_history_snapshot_migrated"
        private const val KEY_ACTIVE_HISTORY_ID = "active_history_id"
        const val STATUS_PENDING = "pending"
        const val STATUS_COOKED = "cooked"
        const val STATUS_CANCELLED = "cancelled"
    }
}

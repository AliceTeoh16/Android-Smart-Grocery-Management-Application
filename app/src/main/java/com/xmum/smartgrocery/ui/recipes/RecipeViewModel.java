package com.xmum.smartgrocery.ui.recipes;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelKt;

import com.google.gson.Gson;
import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;
import com.xmum.smartgrocery.data.repository.ChecklistHistoryRepository;
import com.xmum.smartgrocery.data.repository.GroceryRepository;
import com.xmum.smartgrocery.data.repository.RecipeRepository;
import com.xmum.smartgrocery.data.repository.RecipeRepository.DeductedInventoryItem;
import com.xmum.smartgrocery.data.repository.RecipeRepository.GroceryChecklistResult;
import com.xmum.smartgrocery.data.repository.RecipeRepository.MissingChecklistItem;
import com.xmum.smartgrocery.data.repository.ShoppingListRepository;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.ChecklistShoppingMerge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.CompletableDeferred;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.DelayKt;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;
import kotlinx.coroutines.sync.Mutex;
import kotlinx.coroutines.sync.MutexKt;

public final class RecipeViewModel extends AndroidViewModel {
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COOKED = "cooked";
    public static final String STATUS_CANCELLED = "cancelled";

    private static final String SEARCH_HISTORY_PREFS = "ai_checklist_history";
    private static final String CHECKLIST_PREFS = "ai_checklist_store";
    private static final String SEARCH_HISTORY_KEY = "search_history";
    private static final String SAVED_CHECKLISTS_KEY = "saved_checklists";
    private static final String ROOM_HISTORY_MIGRATED = "room_history_snapshot_migrated";
    private static final String ACTIVE_HISTORY_KEY = "active_history_id";
    private static final String HISTORY_SEPARATOR = "\u001F";
    private static final int MAX_SEARCH_HISTORY = 8;
    private static final Pattern QUANTITY = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
    private static final List<String> PANTRY_TERMS = Arrays.asList("pantry", "seasoning", "seasonings", "spice",
            "spices", "sauce", "soy sauce", "salt", "sugar", "pepper", "oil", "vinegar", "flour", "cornstarch", "starch");
    private static final SharingStarted WHILE_SUBSCRIBED =
            SharingStarted.Companion.WhileSubscribed(5000L, Long.MAX_VALUE);

    private final GroceryRepository grocery;
    private RecipeRepository recipeRepository;
    private final ShoppingListRepository shopping;
    private final ChecklistHistoryRepository history;
    private final SharedPreferences historyPrefs;
    private final SharedPreferences checklistPrefs;
    private final CoroutineScope scope;
    private final Gson gson = new Gson();
    private final Mutex historyOperations = MutexKt.Mutex(false);
    private final CompletableDeferred<Unit> historyReady = AndroidCoroutines.newCompletion();

    private final StateFlow<Integer> warningDays;
    private final StateFlow<List<GroceryItem>> activeInventory;
    private final MutableStateFlow<String> userPrompt = StateFlowKt.MutableStateFlow("");
    private final MutableStateFlow<List<String>> searchHistory;
    private final MutableStateFlow<Boolean> isLoading = StateFlowKt.MutableStateFlow(false);
    private final MutableStateFlow<Boolean> hasGenerated = StateFlowKt.MutableStateFlow(false);
    private final MutableStateFlow<String> generationError = StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<GroceryChecklistResult> checklistResult = StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<List<SelectableChecklistItem>> missingItems =
            StateFlowKt.MutableStateFlow(Collections.emptyList());
    private final MutableStateFlow<List<SelectableInventoryChecklistItem>> inventoryItems =
            StateFlowKt.MutableStateFlow(Collections.emptyList());
    private final MutableStateFlow<String> activeChecklistId = StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<String> activeChecklistStatus = StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<List<SavedChecklist>> savedChecklists = StateFlowKt.MutableStateFlow(Collections.emptyList());
    private final MutableStateFlow<Boolean> clearingHistory = StateFlowKt.MutableStateFlow(false);
    private final MutableStateFlow<Boolean> addingToShopping = StateFlowKt.MutableStateFlow(false);
    private final MutableSharedFlow<String> outgoingMessages = SharedFlowKt.MutableSharedFlow(0, 0, BufferOverflow.SUSPEND);
    private final SharedFlow<String> shoppingAddEvents = FlowKt.asSharedFlow(outgoingMessages);
    private final StateFlow<ChecklistUiState> uiState;

    private SavedChecklist activeSnapshot;
    private Job generationJob;
    private long requestVersion;

    public RecipeViewModel(@NonNull Application application) {
        super(application);
        SmartGroceryApp app = (SmartGroceryApp) application;
        scope = ViewModelKt.getViewModelScope(this);
        grocery = app.getGroceryRepository();
        recipeRepository = app.getRecipeRepository();
        shopping = app.getShoppingListRepository();
        history = app.getChecklistHistoryRepository();
        warningDays = app.getPreferencesRepository().getExpiryWarningDays();
        historyPrefs = application.getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE);
        checklistPrefs = application.getSharedPreferences(CHECKLIST_PREFS, Context.MODE_PRIVATE);
        searchHistory = StateFlowKt.MutableStateFlow(loadSearchHistory());

        Flow<List<GroceryItem>> ingredients = FlowKt.combine(grocery.getAllGroceryItems(), warningDays,
                (items, warning, ignored) -> RecipeRepository.readyIngredients(items, warning));
        activeInventory = FlowKt.stateIn(AndroidCoroutines.background(ingredients), scope, WHILE_SUBSCRIBED, Collections.emptyList());
        Flow<Set<String>> pendingShopping = AndroidCoroutines.background(FlowKt.distinctUntilChanged(
                FlowKt.map(shopping.getShoppingListItems(), (items, ignored) -> ChecklistShoppingMerge.pendingNames(items))));
        Flow<ChecklistUiState> states = AndroidCoroutines.combineStates(
                Arrays.asList(activeInventory, warningDays, userPrompt, searchHistory, isLoading, hasGenerated,
                        generationError, checklistResult, missingItems, activeChecklistStatus, savedChecklists,
                        inventoryItems, clearingHistory, pendingShopping, addingToShopping),
                RecipeViewModel::presentState);
        // Keep prompt/checkbox state delivery on Main; only the expensive read-only matching runs off it.
        uiState = FlowKt.stateIn(states, scope, WHILE_SUBSCRIBED, new ChecklistUiState());

        AndroidCoroutines.<Unit>launch(scope, completion -> AndroidCoroutines.<Unit, Unit>then(
                next -> AndroidCoroutines.<Unit>recoverWith(
                        work -> AndroidCoroutines.<Unit>withLock(historyOperations, this::initializeHistory, work),
                        (error, resumed) -> {
                            historyReady.completeExceptionally(error);
                            generationError.setValue("Couldn’t load checklist history. Reopen this screen to retry.");
                            return Unit.INSTANCE;
                        }, next),
                (unused, next) -> {
                    historyReady.complete(Unit.INSTANCE);
                    AndroidCoroutines.observe(scope, history.getAllHistory(), this::updateHistoryRows);
                    return Unit.INSTANCE;
                }, completion));
    }

    @NonNull
    public RecipeRepository getRecipeRepository() { return recipeRepository; }

    public void setRecipeRepository(@NonNull RecipeRepository repository) {
        recipeRepository = repository;
    }

    @NonNull
    public SharedFlow<String> getShoppingAddEvents() { return shoppingAddEvents; }

    @NonNull
    public StateFlow<ChecklistUiState> getUiState() { return uiState; }

    @SuppressWarnings("unchecked")
    private static ChecklistUiState presentState(Object[] values) {
        Set<String> pending = (Set<String>) values[13];
        boolean editable = STATUS_PENDING.equals(values[9]) && !(Boolean) values[4] && !(Boolean) values[14];
        List<SelectableChecklistItem> missing = new ArrayList<>();
        int selected = 0;
        for (SelectableChecklistItem row : (List<SelectableChecklistItem>) values[8]) {
            missing.add(new SelectableChecklistItem(row.getChecklistItem(), row.isSelected(),
                    ChecklistShoppingMerge.isPending(row.getChecklistItem().getItem(), pending), editable));
            if (row.isSelected()) {
                selected++;
            }
        }
        List<SelectableInventoryChecklistItem> inventory = new ArrayList<>();
        for (SelectableInventoryChecklistItem row : (List<SelectableInventoryChecklistItem>) values[11]) {
            inventory.add(new SelectableInventoryChecklistItem(row.getInventoryItem(), row.isSelected(),
                    ChecklistShoppingMerge.isPending(row.getInventoryItem().getItem(), pending)));
        }
        return new ChecklistUiState((List<GroceryItem>) values[0], (Integer) values[1], (String) values[2],
                (List<String>) values[3], (Boolean) values[4], (Boolean) values[5], (String) values[6],
                (GroceryChecklistResult) values[7], missing, selected, (String) values[9], (List<SavedChecklist>) values[10],
                inventory, (Boolean) values[12], (Boolean) values[14]);
    }

    private Object initializeHistory(Continuation<? super Unit> completion) {
        return AndroidCoroutines.<List<ChecklistHistoryItem>, Unit>then(history::getAllHistorySync,
                (rows, next) -> AndroidCoroutines.<Unit, Unit>then(
                        migrate -> migrateHistory(rows, migrate), (unused, prune) ->
                                AndroidCoroutines.<Unit, Unit>then(history::pruneToLatest,
                                        (pruned, read) -> AndroidCoroutines.<List<ChecklistHistoryItem>, Unit>then(
                                                history::getAllHistorySync,
                                                (updated, finish) -> {
                                                    finishHistorySetup(updated);
                                                    return Unit.INSTANCE;
                                                }, read), prune), next), completion);
    }

    private Object migrateHistory(List<ChecklistHistoryItem> rows, Continuation<? super Unit> completion) {
        boolean importLegacy = rows.isEmpty() && !checklistPrefs.getBoolean(ROOM_HISTORY_MIGRATED, false);
        boolean backfill = rows.stream().anyMatch(row -> StringsKt.isBlank(row.getStateJson()));
        Map<String, SavedChecklist> legacy = importLegacy || backfill ? loadLegacyHistory() : Collections.emptyMap();
        if (importLegacy) {
            List<SavedChecklist> ordered = new ArrayList<>(legacy.values());
            ordered.sort(Comparator.comparingLong(SavedChecklist::getCreatedAt));
            return AndroidCoroutines.forEachAsync(ordered,
                    (saved, next) -> history.saveChecklistWithPruning(ChecklistHistorySnapshot.encode(saved), next), completion);
        }
        return AndroidCoroutines.forEachAsync(rows, (row, next) -> {
            if (!StringsKt.isBlank(row.getStateJson())) {
                return Unit.INSTANCE;
            }
            SavedChecklist saved = ChecklistHistorySnapshot.decode(row, legacy.get(row.getId()));
            return history.backfillState(ChecklistHistorySnapshot.encode(saved), next);
        }, completion);
    }

    private void finishHistorySetup(List<ChecklistHistoryItem> rows) {
        updateHistoryRows(rows);
        checklistPrefs.edit().putBoolean(ROOM_HISTORY_MIGRATED, true).remove(SAVED_CHECKLISTS_KEY).apply();
        if (requestVersion == 0L) {
            String activeId = checklistPrefs.getString(ACTIVE_HISTORY_KEY, null);
            SavedChecklist pending = null;
            for (SavedChecklist saved : savedChecklists.getValue()) {
                if (saved.getId().equals(activeId)) {
                    hydrate(saved);
                    return;
                }
                if (pending == null && STATUS_PENDING.equals(saved.getStatus())) {
                    pending = saved;
                }
            }
            if (pending != null) {
                hydrate(pending);
            }
        }
    }

    private void updateHistoryRows(List<ChecklistHistoryItem> rows) {
        List<SavedChecklist> decoded = new ArrayList<>(rows.size());
        for (ChecklistHistoryItem row : rows) {
            decoded.add(ChecklistHistorySnapshot.decode(row));
        }
        savedChecklists.setValue(decoded);
    }

    private Map<String, SavedChecklist> loadLegacyHistory() {
        String raw = checklistPrefs.getString(SAVED_CHECKLISTS_KEY, "");
        if (raw == null || StringsKt.isBlank(raw)) {
            return Collections.emptyMap();
        }
        Map<String, SavedChecklist> saved = new LinkedHashMap<>();
        try {
            SavedChecklist[] decoded = gson.fromJson(raw, SavedChecklist[].class);
            if (decoded != null) {
                for (SavedChecklist item : decoded) {
                    saved.put(item.getId(), item);
                }
            }
        } catch (RuntimeException invalid) {
            return Collections.emptyMap();
        }
        return saved;
    }

    public void setPrompt(@NonNull String prompt) {
        userPrompt.setValue(prompt);
    }


    public void generateChecklist() {
        generateChecklist(null);
    }

    public void generateChecklist(@Nullable String promptOverride) {
        if (clearingHistory.getValue()) {
            return;
        }
        long version = ++requestVersion;
        cancelGeneration();
        generationJob = AndroidCoroutines.<Unit>launch(scope, next -> generate(promptOverride, version, next));
    }

    private Object generate(String promptOverride, long version, Continuation<? super Unit> completion) {
        String prompt = promptOverride != null && !StringsKt.isBlank(promptOverride) ? promptOverride : trim(userPrompt.getValue());
        userPrompt.setValue(prompt);
        if (StringsKt.isBlank(prompt)) {
            isLoading.setValue(false);
            clearResult();
            generationError.setValue("Enter a dish request first, for example: Tomato Beef Noodles for 4 people.");
            hasGenerated.setValue(true);
            return Unit.INSTANCE;
        }

        isLoading.setValue(true);
        generationError.setValue(null);
        return AndroidCoroutines.<Unit>withFinally(next -> AndroidCoroutines.<Unit>recoverWith(
                work -> generateAndSave(prompt, version, work),
                (error, resumed) -> {
                    if (version == requestVersion) {
                        clearResult();
                        generationError.setValue(error.getMessage() != null ? error.getMessage() : "AI checklist generation failed.");
                    }
                    return Unit.INSTANCE;
                }, next), () -> {
                    if (version == requestVersion) {
                        hasGenerated.setValue(true);
                        isLoading.setValue(false);
                    }
                }, completion);
    }

    private Object generateAndSave(String prompt, long version, Continuation<? super Unit> completion) {
        return AndroidCoroutines.<Unit, Unit>then(next -> DelayKt.delay(250L, next),
                (waited, generate) -> AndroidCoroutines.<GroceryChecklistResult, Unit>then(
                        next -> recipeRepository.generateChecklistAssistantResult(activeInventory.getValue(), prompt,
                                warningDays.getValue(), next),
                        (result, next) -> AndroidCoroutines.<Unit, Unit>then(historyReady::await,
                                (ready, save) -> saveGenerated(result, prompt, version, save), next), generate), completion);
    }

    private Object saveGenerated(GroceryChecklistResult result, String prompt, long version,
            Continuation<? super Unit> completion) {
        long now = System.currentTimeMillis();
        SavedChecklist saved = new SavedChecklist(ChecklistHistorySnapshot.newId(now), result.getDishName(),
                result.getServings(), STATUS_PENDING, now, result.getMissingChecklist(), result.getInventoryDeducted(),
                Collections.emptyList(), result.getIngredients(), result.getSteps(), Collections.emptyList(),
                Collections.emptyList(), prompt);
        return AndroidCoroutines.<Unit, Unit>then(
                next -> AndroidCoroutines.withLock(historyOperations, acquired ->
                        AndroidCoroutines.<Unit, Unit>then(
                                write -> history.saveChecklistWithPruning(ChecklistHistorySnapshot.encode(saved), write),
                                (unused, hydrated) -> {
                                    if (version == requestVersion) {
                                        hydrate(saved);
                                    }
                                    return Unit.INSTANCE;
                                }, acquired), next),
                (unused, next) -> {
                    saveSuccessfulSearch(prompt);
                    generationError.setValue(null);
                    return Unit.INSTANCE;
                }, completion);
    }

    public void setChecklistItemSelected(@NonNull MissingChecklistItem item, boolean selected) {
        if (!selectionEditable()) {
            return;
        }
        String selectedKey = ChecklistSelection.missingKey(item);
        List<SelectableChecklistItem> rows = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        for (SelectableChecklistItem row : missingItems.getValue()) {
            String key = ChecklistSelection.missingKey(row.getChecklistItem());
            if (key.equals(selectedKey)) {
                row = new SelectableChecklistItem(row.getChecklistItem(), selected, row.isAddedToShopping(), row.isEditable());
            }
            if (!row.isSelected()) {
                unchecked.add(key);
            }
            rows.add(row);
        }
        missingItems.setValue(rows);
        SavedChecklist snapshot = activeSnapshot;
        if (snapshot != null) {
            updateSnapshotLocally(withChoices(snapshot, snapshot.getStatus(), snapshot.getAddedShoppingItemKeys(),
                    snapshot.getUncheckedInventoryItemKeys(), unchecked));
        }
    }

    public void setInventoryItemSelected(@NonNull DeductedInventoryItem item, boolean selected) {
        if (!selectionEditable()) {
            return;
        }
        String selectedKey = ChecklistSelection.inventoryKey(item);
        List<SelectableInventoryChecklistItem> rows = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        for (SelectableInventoryChecklistItem row : inventoryItems.getValue()) {
            String key = ChecklistSelection.inventoryKey(row.getInventoryItem());
            if (key.equals(selectedKey)) {
                row = new SelectableInventoryChecklistItem(row.getInventoryItem(), selected, row.isAddedToShopping());
            }
            if (!row.isSelected()) {
                unchecked.add(key);
            }
            rows.add(row);
        }
        inventoryItems.setValue(rows);
        SavedChecklist snapshot = activeSnapshot;
        if (snapshot != null) {
            updateSnapshotLocally(withChoices(snapshot, snapshot.getStatus(), snapshot.getAddedShoppingItemKeys(),
                    unchecked, snapshot.getUncheckedMissingItemKeys()));
        }
    }

    public void addSelectedMissingItemsToShoppingList(@NonNull String assignedTo) {
        SavedChecklist source = activeSnapshot;
        if (addingToShopping.getValue() || clearingHistory.getValue() || isLoading.getValue()
                || source == null || !STATUS_PENDING.equals(source.getStatus())) {
            return;
        }

        List<MissingChecklistItem> selectedMissing = new ArrayList<>();
        List<DeductedInventoryItem> selectedInventory = new ArrayList<>();
        for (SelectableChecklistItem row : missingItems.getValue()) {
            if (row.isSelected()) {
                selectedMissing.add(row.getChecklistItem());
            }
        }
        for (SelectableInventoryChecklistItem row : inventoryItems.getValue()) {
            if (row.isSelected()) {
                selectedInventory.add(row.getInventoryItem());
            }
        }
        List<MissingChecklistItem> selected = new ArrayList<>(selectedMissing);
        List<GroceryItem> stock = activeInventory.getValue();
        for (DeductedInventoryItem item : selectedInventory) {
            selected.add(ChecklistSelection.asShoppingItem(item, stock));
        }
        if (selected.isEmpty()) {
            AndroidCoroutines.emit(scope, outgoingMessages, "No items selected.");
            return;
        }

        addingToShopping.setValue(true);
        AndroidCoroutines.<Unit>launch(scope, completion -> AndroidCoroutines.<Unit>withFinally(
                next -> AndroidCoroutines.<Unit>recoverWith(
                        write -> AndroidCoroutines.<Unit, Unit>then(
                                add -> shopping.addAiChecklistItems(selected, assignedTo, add),
                                (unused, persist) -> finalizeShoppingAddition(
                                        source, selectedMissing, selectedInventory, selected.size(), assignedTo, persist), write),
                        (error, show) -> message(error.getMessage() != null ? error.getMessage()
                                : "Couldn’t add the selected items. Please try again.", show), next),
                () -> addingToShopping.setValue(false), completion));
    }

    private Object finalizeShoppingAddition(SavedChecklist source, List<MissingChecklistItem> selectedMissing,
            List<DeductedInventoryItem> selectedInventory, int count, String assignedTo, Continuation<? super Unit> completion) {
        String target = "Me".equalsIgnoreCase(assignedTo) ? "your Personal Shopping List" : "the Household Shopping List";
        return AndroidCoroutines.<String, Unit>then(
                next -> AndroidCoroutines.<String>recoverCall(
                        work -> AndroidCoroutines.<Unit, String>then(
                                persist -> markShoppingAdditions(source, selectedMissing, selectedInventory, persist),
                                (unused, done) -> count + " item(s) added to " + target + "!", work),
                        error -> "Items were added, but checklist choices couldn’t be saved.", next),
                (status, show) -> message(status, show), completion);
    }

    private Object markShoppingAdditions(SavedChecklist source, List<MissingChecklistItem> missing,
            List<DeductedInventoryItem> inventory, Continuation<? super Unit> completion) {
        Set<String> added = new LinkedHashSet<>();
        for (MissingChecklistItem item : missing) {
            added.add(ChecklistSelection.missingKey(item));
        }
        for (DeductedInventoryItem item : inventory) {
            added.add(ChecklistSelection.inventoryKey(item));
        }
        boolean isActive = source.getId().equals(activeChecklistId.getValue());
        SavedChecklist current = isActive && activeSnapshot != null ? activeSnapshot : source;
        Set<String> previous = new LinkedHashSet<>(orEmpty(current.getAddedShoppingItemKeys()));
        previous.addAll(added);
        Set<String> unchecked = new LinkedHashSet<>(orEmpty(current.getUncheckedMissingItemKeys()));
        for (MissingChecklistItem item : missing) {
            unchecked.add(ChecklistSelection.missingKey(item));
        }
        SavedChecklist updated = withChoices(current, current.getStatus(), new ArrayList<>(previous),
                current.getUncheckedInventoryItemKeys(), new ArrayList<>(unchecked));
        if (isActive) {
            List<SelectableChecklistItem> rows = new ArrayList<>();
            for (SelectableChecklistItem row : missingItems.getValue()) {
                rows.add(added.contains(ChecklistSelection.missingKey(row.getChecklistItem()))
                        ? new SelectableChecklistItem(row.getChecklistItem(), false, row.isAddedToShopping(), row.isEditable()) : row);
            }
            missingItems.setValue(rows);
            activeSnapshot = updated;
        }
        replaceInHistory(updated);
        return persist(updated, null, completion);
    }

    public void markActiveChecklistCooked() {
        String id = activeChecklistId.getValue();
        if (id != null) {
            markChecklistCookedById(id);
        }
    }

    public void markChecklistCookedById(@NonNull String id) {
        AndroidCoroutines.<Unit>launch(scope, completion -> AndroidCoroutines.<SavedChecklist, Unit>then(
                read -> readChecklist(id, read),
                (current, next) -> {
                    if (current == null || !STATUS_PENDING.equals(current.getStatus())) {
                        return Unit.INSTANCE;
                    }
                    List<DeductedInventoryItem> selected = new ArrayList<>();
                    List<String> unchecked = orEmpty(current.getUncheckedInventoryItemKeys());
                    for (DeductedInventoryItem item : current.getInventoryDeducted()) {
                        if (!unchecked.contains(ChecklistSelection.inventoryKey(item))) {
                            selected.add(item);
                        }
                    }
                    String result = selected.isEmpty() ? "Meal marked as cooked. No inventory items selected for deduction."
                            : "Meal marked as cooked! Inventory updated.";
                    return AndroidCoroutines.<Unit, Unit>then(
                            deduct -> AndroidCoroutines.forEachAsync(selected, this::deductInventory, deduct),
                            (unused, commit) -> markStatus(current, STATUS_COOKED, result, commit), next);
                }, completion));
    }

    public void cancelChecklistById(@NonNull String id) {
        AndroidCoroutines.<Unit>launch(scope, completion -> AndroidCoroutines.<SavedChecklist, Unit>then(
                read -> readChecklist(id, read),
                (current, next) -> current == null ? Unit.INSTANCE
                        : markStatus(current, STATUS_CANCELLED, "Recipe marked as cancelled. No inventory deducted.", next),
                completion));
    }

    private Object readChecklist(String id, Continuation<? super SavedChecklist> completion) {
        return AndroidCoroutines.<Unit, SavedChecklist>then(historyReady::await, (ready, next) -> {
            if (activeSnapshot != null && id.equals(activeSnapshot.getId())) {
                return activeSnapshot;
            }
            return AndroidCoroutines.<ChecklistHistoryItem, SavedChecklist>then(read -> history.getById(id, read),
                    (row, unused) -> row == null ? null : ChecklistHistorySnapshot.decode(row), next);
        }, completion);
    }

    private Object deductInventory(DeductedInventoryItem item, Continuation<? super Unit> completion) {
        String original = item.getItem();
        String cleaned = trim(PARENTHETICAL.matcher(original).replaceAll(""));
        return AndroidCoroutines.<GroceryItem, Unit>then(
                read -> AndroidCoroutines.<GroceryItem, GroceryItem>then(
                        exact -> grocery.findBestMatchingItem(cleaned, exact),
                        (stock, fallback) -> stock != null ? stock : grocery.findBestMatchingItem(original, fallback), read),
                (stock, write) -> stock == null ? Unit.INSTANCE
                        : grocery.consumeQuantityFefo(stock.getName(), cookingUnits(stock, item), write), completion);
    }

    private static int cookingUnits(GroceryItem stock, DeductedInventoryItem item) {
        if (isPantry(stock.getCategory()) || isPantry(stock.getName()) || isPantry(item.getItem())) {
            return 1;
        }
        String quantity = item.getUsedQuantity();
        Matcher first = QUANTITY.matcher(quantity);
        Double amount = first.find() ? StringsKt.toDoubleOrNull(first.group()) : null;
        String lower = quantity.toLowerCase(Locale.ROOT);
        if (amount == null || lower.contains("g") || lower.contains("ml")
                || lower.contains("tbsp") || lower.contains("tsp")) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(amount));
    }

    private static boolean isPantry(String value) {
        String name = value.toLowerCase(Locale.ROOT);
        return PANTRY_TERMS.stream().anyMatch(name::contains);
    }

    private Object markStatus(SavedChecklist source, String status, String result, Continuation<? super Unit> completion) {
        SavedChecklist updated = withChoices(source, status, source.getAddedShoppingItemKeys(),
                source.getUncheckedInventoryItemKeys(), source.getUncheckedMissingItemKeys());
        replaceInHistory(updated);
        return AndroidCoroutines.<Unit, Unit>then(next -> persist(updated, status, next),
                (unused, show) -> {
                    if (source.getId().equals(activeChecklistId.getValue())) {
                        clearActiveChecklist();
                    }
                    return message(result, show);
                }, completion);
    }

    public void restoreChecklistById(@NonNull String id) {
        restoreChecklistById(id, () -> {});
    }

    /** Restores a saved generation by identity from Room. */
    public void restoreChecklistById(@NonNull String id, @NonNull Runnable onRestored) {
        if (clearingHistory.getValue()) {
            return;
        }
        long version = ++requestVersion;
        cancelGeneration();
        isLoading.setValue(false);
        AndroidCoroutines.<Unit>launch(scope, next -> AndroidCoroutines.<Unit>recoverWith(
                load -> AndroidCoroutines.<Unit, Unit>then(historyReady::await,
                        (ready, locked) -> AndroidCoroutines.withLock(historyOperations,
                                work -> AndroidCoroutines.<ChecklistHistoryItem, Unit>then(
                                        read -> history.getById(id, read), (row, show) -> {
                                            if (version != requestVersion) {
                                                return Unit.INSTANCE;
                                            }
                                            if (row == null) {
                                                return message("This checklist is no longer in recent history.", show);
                                            }
                                            hydrate(ChecklistHistorySnapshot.decode(row));
                                            onRestored.run();
                                            return Unit.INSTANCE;
                                        }, work), locked), load),
                (error, resumed) -> message("Couldn’t reopen this checklist. Please try again.", resumed), next));
    }

    public void showSavedChecklist(@NonNull SavedChecklist checklist) {
        restoreChecklistById(checklist.getId());
    }

    /** Clears history only after the user confirms. */
    public void clearAllChecklistHistory() {
        if (clearingHistory.getValue()) {
            return;
        }
        clearingHistory.setValue(true);
        requestVersion++;
        cancelGeneration();
        isLoading.setValue(false);
        AndroidCoroutines.<Unit>launch(scope, next -> AndroidCoroutines.<Unit>withFinally(
                clear -> AndroidCoroutines.<Unit>recoverWith(
                        await -> AndroidCoroutines.<Unit, Unit>then(historyReady::await,
                                (ready, lock) -> AndroidCoroutines.withLock(historyOperations, write ->
                                        AndroidCoroutines.<Unit, Unit>then(history::clearAll, (unused, reset) -> {
                                            savedChecklists.setValue(Collections.emptyList());
                                            clearActiveChecklist();
                                            checklistPrefs.edit().putBoolean(ROOM_HISTORY_MIGRATED, true)
                                                    .remove(SAVED_CHECKLISTS_KEY).apply();
                                            return Unit.INSTANCE;
                                        }, write), lock), await),
                        (error, show) -> message("Couldn’t clear checklist history. Please try again.", show), clear),
                () -> clearingHistory.setValue(false), next));
    }

    private void hydrate(SavedChecklist snapshot) {
        activeSnapshot = snapshot;
        activeChecklistId.setValue(snapshot.getId());
        activeChecklistStatus.setValue(snapshot.getStatus());
        checklistResult.setValue(toResult(snapshot));
        List<SelectableChecklistItem> missing = new ArrayList<>();
        List<String> uncheckedMissing = orEmpty(snapshot.getUncheckedMissingItemKeys());
        for (MissingChecklistItem item : snapshot.getMissingChecklist()) {
            missing.add(new SelectableChecklistItem(item, !uncheckedMissing.contains(ChecklistSelection.missingKey(item)),
                    false, STATUS_PENDING.equals(snapshot.getStatus())));
        }
        missingItems.setValue(missing);
        List<SelectableInventoryChecklistItem> inventory = new ArrayList<>();
        List<String> uncheckedInventory = orEmpty(snapshot.getUncheckedInventoryItemKeys());
        for (DeductedInventoryItem item : snapshot.getInventoryDeducted()) {
            inventory.add(new SelectableInventoryChecklistItem(item,
                    !uncheckedInventory.contains(ChecklistSelection.inventoryKey(item)), false));
        }
        inventoryItems.setValue(inventory);
        generationError.setValue(null);
        hasGenerated.setValue(true);
        isLoading.setValue(false);
        userPrompt.setValue(snapshot.getUserPrompt() != null ? snapshot.getUserPrompt() : snapshot.getDishName());
        checklistPrefs.edit().putString(ACTIVE_HISTORY_KEY, snapshot.getId()).apply();
    }

    private void updateSnapshotLocally(SavedChecklist snapshot) {
        activeSnapshot = snapshot;
        replaceInHistory(snapshot);
        AndroidCoroutines.<Unit>launch(scope, next -> AndroidCoroutines.<Unit>recoverWith(
                write -> persist(snapshot, null, write),
                (error, show) -> message("Couldn’t save checklist choices. Please try again.", show), next));
    }

    private void replaceInHistory(SavedChecklist snapshot) {
        List<SavedChecklist> updated = new ArrayList<>();
        for (SavedChecklist row : savedChecklists.getValue()) {
            updated.add(row.getId().equals(snapshot.getId()) ? snapshot : row);
        }
        savedChecklists.setValue(updated);
    }

    private Object persist(SavedChecklist snapshot, String status, Continuation<? super Unit> completion) {
        return AndroidCoroutines.<Unit, Unit>then(historyReady::await,
                (ready, next) -> AndroidCoroutines.withLock(historyOperations,
                        write -> history.updateState(ChecklistHistorySnapshot.encode(snapshot), status, write), next), completion);
    }

    private void clearActiveChecklist() {
        activeSnapshot = null;
        checklistPrefs.edit().remove(ACTIVE_HISTORY_KEY).apply();
        activeChecklistId.setValue(null);
        activeChecklistStatus.setValue(null);
        clearResult();
        generationError.setValue(null);
        hasGenerated.setValue(false);
        userPrompt.setValue("");
    }

    private void clearResult() {
        checklistResult.setValue(null);
        missingItems.setValue(Collections.emptyList());
        inventoryItems.setValue(Collections.emptyList());
    }

    private boolean selectionEditable() {
        return STATUS_PENDING.equals(activeChecklistStatus.getValue()) && !isLoading.getValue() && !addingToShopping.getValue();
    }

    private void cancelGeneration() {
        if (generationJob != null) {
            generationJob.cancel(null);
        }
    }

    private void saveSuccessfulSearch(String query) {
        String normalized = trim(query);
        if (StringsKt.isBlank(normalized)) {
            return;
        }
        List<String> updated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        updated.add(normalized);
        seen.add(normalized.toLowerCase(Locale.ROOT));
        for (String entry : searchHistory.getValue()) {
            if (seen.add(entry.toLowerCase(Locale.ROOT))) {
                updated.add(entry);
                if (updated.size() == MAX_SEARCH_HISTORY) {
                    break;
                }
            }
        }
        searchHistory.setValue(updated);
        historyPrefs.edit().putString(SEARCH_HISTORY_KEY, android.text.TextUtils.join(HISTORY_SEPARATOR, updated)).apply();
    }

    private List<String> loadSearchHistory() {
        String stored = historyPrefs.getString(SEARCH_HISTORY_KEY, "");
        List<String> result = new ArrayList<>();
        if (stored != null) {
            for (String value : stored.split(HISTORY_SEPARATOR)) {
                String query = trim(value);
                if (!StringsKt.isBlank(query)) {
                    result.add(query);
                    if (result.size() == MAX_SEARCH_HISTORY) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    private static GroceryChecklistResult toResult(SavedChecklist snapshot) {
        List<String> covered = new ArrayList<>();
        for (DeductedInventoryItem item : snapshot.getInventoryDeducted()) {
            covered.add(item.getItem() + " " + item.getUsedQuantity());
        }
        return new GroceryChecklistResult(snapshot.getDishName(), snapshot.getServings(), snapshot.getMissingChecklist(),
                covered, snapshot.getInventoryDeducted(), orEmpty(snapshot.getIngredients()), orEmpty(snapshot.getSteps()));
    }

    private static SavedChecklist withChoices(SavedChecklist snapshot, String status, List<String> added,
            List<String> uncheckedInventory, List<String> uncheckedMissing) {
        return snapshot.copy(snapshot.getId(), snapshot.getDishName(), snapshot.getServings(), status,
                snapshot.getCreatedAt(), snapshot.getMissingChecklist(), snapshot.getInventoryDeducted(),
                added, snapshot.getIngredients(), snapshot.getSteps(), uncheckedInventory, uncheckedMissing, snapshot.getUserPrompt());
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? Collections.emptyList() : value;
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }

    private Object message(String text, Continuation<? super Unit> completion) {
        return outgoingMessages.emit(text, completion);
    }
}

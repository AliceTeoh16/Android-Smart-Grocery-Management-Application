package com.xmum.smartgrocery.ui.quickadd;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelKt;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.ExpiryEstimator;
import com.xmum.smartgrocery.utils.GroceryCategories;
import com.xmum.smartgrocery.utils.SuspendTransform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;
import retrofit2.HttpException;

/** Keeps recognized and manual drafts until a confirmed batch is committed. */
public final class IngredientScanViewModel extends AndroidViewModel {
    private static final String ENGINE_KEY = "scan_engine";
    private static final String ENGINE = "visual-food-v1";
    private static final String BATCH_ROWS = "batch_rows";
    private static final String REVIEWING = "reviewing";
    private static final String SAVED_COUNT = "saved_count";
    private static final String FOOD_CATEGORY = "Food & Beverage";

    private final SmartGroceryApp app;
    private final SavedStateHandle savedState;
    private final CoroutineScope scope;
    private final MutableStateFlow<IngredientScanState> mutableState;
    private final StateFlow<IngredientScanState> state;
    private SuspendTransform<Uri, List<VisualFoodItem>> recognizer;
    private Job scanJob;
    private boolean completionDelivered;

    public IngredientScanViewModel(@NonNull Application application, @NonNull SavedStateHandle savedState) {
        super(application);
        app = (SmartGroceryApp) application;
        this.savedState = savedState;
        scope = ViewModelKt.getViewModelScope(this);
        mutableState = StateFlowKt.MutableStateFlow(restore(savedState));
        state = FlowKt.asStateFlow(mutableState);
        recognizer = new VisualFoodRecognition(application)::recognize;
    }

    @NonNull
    public StateFlow<IngredientScanState> getState() {
        return state;
    }

    void useRecognizer(@NonNull SuspendTransform<Uri, List<VisualFoodItem>> replacement) {
        recognizer = replacement;
    }

    public void scan(@NonNull Uri uri) {
        IngredientScanState current = state.getValue();
        if (current.getSaving() || current.getSavedCount() > 0) {
            return;
        }
        if (scanJob != null) {
            scanJob.cancel(null);
        }
        update(new IngredientScanState(true, false, Collections.emptyList(), false, 0, null));
        scanJob = AndroidCoroutines.<Unit>launchWithError(scope, next ->
                AndroidCoroutines.<List<VisualFoodItem>, Unit>then(
                        run -> recognizer.invoke(uri, run),
                        (foods, show) -> {
                            showRecognizedItems(foods);
                            return Unit.INSTANCE;
                        }, next),
                error -> update(new IngredientScanState(false, false, Collections.emptyList(), false, 0, scanError(error))));
    }

    private void showRecognizedItems(List<VisualFoodItem> foods) {
        List<BatchIngredientDraft> rows = new ArrayList<>(foods.size());
        for (VisualFoodItem food : foods) {
            String category = GroceryCategories.recognizedSelection(food.getName(), food.getCategory(), food.getSubcategory());
            Integer quantity = food.getQuantity();
            rows.add(new BatchIngredientDraft(UUID.randomUUID().toString(), food.getName(), category,
                    quantity != null ? quantity : 1, ExpiryEstimator.estimateExpiryTimestamp(food.getName(), category),
                    true, quantity != null, false, false, GroceryCategories.defaultThreshold(category)));
        }
        update(new IngredientScanState(false, true, rows, false, 0, rows.isEmpty()
                ? "No items were confidently identified. Add missed items manually or try another photo." : null));
    }

    public void rename(@NonNull String id, @NonNull String name) {
        String shortened = name.substring(0, Math.min(100, name.length()));
        edit(id, row -> row.copy(row.getId(), shortened, row.getCategory(), row.getQuantity(),
                row.getExpiryManuallySelected() ? row.getExpiryDate()
                        : ExpiryEstimator.estimateExpiryTimestamp(shortened, row.getCategory()),
                row.getSelected(), row.getQuantityDetected(), row.getExpiryManuallySelected(),
                row.getManuallyAdded(), row.getThreshold()));
    }

    public void changeCategory(@NonNull String id, @NonNull String category) {
        if (!GroceryCategories.all.contains(category)) {
            return;
        }
        String selection = GroceryCategories.selection(category);
        if (GroceryCategories.all.contains(selection)) {
            edit(id, row -> row.copy(row.getId(), row.getName(), selection, row.getQuantity(),
                    row.getExpiryManuallySelected() ? row.getExpiryDate()
                            : ExpiryEstimator.estimateExpiryTimestamp(row.getName(), category),
                    row.getSelected(), row.getQuantityDetected(), row.getExpiryManuallySelected(),
                    row.getManuallyAdded(), GroceryCategories.defaultThreshold(selection)));
        }
    }

    public void changeExpiry(@NonNull String id, @Nullable Long date) {
        if (date == null || date > 0L) {
            edit(id, row -> row.copy(row.getId(), row.getName(), row.getCategory(), row.getQuantity(), date,
                    row.getSelected(), row.getQuantityDetected(), true, row.getManuallyAdded(), row.getThreshold()));
        }
    }

    public void addManualItem() {
        IngredientScanState current = state.getValue();
        if (current.getSaving() || current.getScanning() || current.getSavedCount() > 0) {
            return;
        }
        if (current.getItems().size() >= 50) {
            showError("You can review up to 50 items in one batch.");
            return;
        }
        List<BatchIngredientDraft> rows = new ArrayList<>(current.getItems());
        rows.add(new BatchIngredientDraft(UUID.randomUUID().toString(), "", FOOD_CATEGORY, 1, null,
                true, false, false, true, GroceryCategories.defaultThreshold(FOOD_CATEGORY)));
        update(current.copy(current.getScanning(), true, rows, current.getSaving(), current.getSavedCount(), null));
    }

    public void adjustQuantity(@NonNull String id, int delta) {
        edit(id, row -> row.copy(row.getId(), row.getName(), row.getCategory(),
                (int) Math.max(1L, Math.min(999L, (long) row.getQuantity() + delta)), row.getExpiryDate(),
                row.getSelected(), true, row.getExpiryManuallySelected(), row.getManuallyAdded(), row.getThreshold()));
    }

    public void select(@NonNull String id, boolean selected) {
        edit(id, row -> row.copy(row.getId(), row.getName(), row.getCategory(), row.getQuantity(), row.getExpiryDate(),
                selected, row.getQuantityDetected(), row.getExpiryManuallySelected(), row.getManuallyAdded(), row.getThreshold()));
    }

    public void delete(@NonNull String id) {
        IngredientScanState current = state.getValue();
        if (!current.getSaving() && current.getSavedCount() == 0) {
            List<BatchIngredientDraft> rows = new ArrayList<>();
            for (BatchIngredientDraft row : current.getItems()) {
                if (!row.getId().equals(id)) {
                    rows.add(row);
                }
            }
            update(current.copy(current.getScanning(), current.getReviewing(), rows, false, 0, null));
        }
    }

    public void showError(@NonNull String message) {
        IngredientScanState current = state.getValue();
        update(current.copy(current.getScanning(), current.getReviewing(), current.getItems(),
                current.getSaving(), current.getSavedCount(), message));
    }

    public void addAllToInventory() {
        addAllToInventory(null);
    }

    public void addAllToInventory(@Nullable DataScope target) {
        IngredientScanState current = state.getValue();
        if (!canSave(current)) {
            return;
        }
        List<BatchIngredientDraft> selected = new ArrayList<>();
        for (BatchIngredientDraft row : current.getItems()) {
            if (row.getSelected()) {
                selected.add(row);
            }
        }
        update(current.copy(current.getScanning(), current.getReviewing(), current.getItems(), true, 0, null));
        AndroidCoroutines.<Unit>launchWithError(scope, next -> saveBatch(selected, target, next),
                error -> finishSave(0, "Couldn’t save this batch. No items were added. Your edits are kept; please try again."));
    }

    private Object saveBatch(List<BatchIngredientDraft> selected, DataScope target, Continuation<? super Unit> completion) {
        List<GroceryItem> items = new ArrayList<>(selected.size());
        for (BatchIngredientDraft row : selected) {
            items.add(new GroceryItem(0, StringsKt.trim(row.getName()).toString(), row.getQuantity(), 0.0,
                    row.getCategory(), row.getExpiryDate(), row.getThreshold(), System.currentTimeMillis(),
                    null, "", null, null, null, null, null));
        }
        return AndroidCoroutines.<List<Long>, Unit>then(
                next -> target == null ? app.getGroceryRepository().insertBatch(items, next)
                        : app.getSessionManager().runInInventoryDestination(
                                target, scoped -> app.getGroceryRepository().insertBatch(items, scoped), next),
                (ids, show) -> {
                    finishSave(items.size(), null);
                    return Unit.INSTANCE;
                }, completion);
    }

    private void finishSave(int count, String error) {
        IngredientScanState current = state.getValue();
        update(current.copy(current.getScanning(), current.getReviewing(), current.getItems(), false, count, error));
    }

    @Nullable
    Integer takeSavedCount() {
        int count = state.getValue().getSavedCount();
        if (completionDelivered || count <= 0) {
            return null;
        }
        completionDelivered = true;
        return count;
    }

    static boolean canSave(@NonNull IngredientScanState state) {
        if (state.getScanning() || state.getSaving() || state.getSavedCount() != 0) {
            return false;
        }
        boolean selected = false;
        for (BatchIngredientDraft row : state.getItems()) {
            if (!row.getSelected()) {
                continue;
            }
            selected = true;
            Long date = row.getExpiryDate();
            if (StringsKt.isBlank(row.getName()) || row.getQuantity() < 1 || row.getQuantity() > 999
                    || !GroceryCategories.all.contains(row.getCategory()) || row.getThreshold() < 0
                    || row.getThreshold() > 999 || (date != null && date <= 0L)) {
                return false;
            }
        }
        return selected;
    }

    private void edit(String id, UnaryOperator<BatchIngredientDraft> edit) {
        IngredientScanState current = state.getValue();
        if (current.getSaving() || current.getSavedCount() != 0) {
            return;
        }
        List<BatchIngredientDraft> rows = new ArrayList<>();
        for (BatchIngredientDraft row : current.getItems()) {
            rows.add(row.getId().equals(id) ? edit.apply(row) : row);
        }
        update(current.copy(current.getScanning(), current.getReviewing(), rows, false, 0, null));
    }

    private void update(IngredientScanState value) {
        ArrayList<Bundle> rows = new ArrayList<>();
        for (BatchIngredientDraft item : value.getItems()) {
            Bundle row = new Bundle();
            row.putString("id", item.getId());
            row.putString("name", item.getName());
            row.putString("category", item.getCategory());
            row.putInt("quantity", item.getQuantity());
            row.putBoolean("selected", item.getSelected());
            row.putBoolean("quantity_detected", item.getQuantityDetected());
            row.putLong("expiry_date", item.getExpiryDate() == null ? 0L : item.getExpiryDate());
            row.putInt("threshold", item.getThreshold());
            row.putBoolean("manual_expiry", item.getExpiryManuallySelected());
            row.putBoolean("manual_item", item.getManuallyAdded());
            rows.add(row);
        }
        savedState.set(ENGINE_KEY, ENGINE);
        savedState.set(BATCH_ROWS, rows);
        savedState.set(REVIEWING, value.getReviewing());
        savedState.set(SAVED_COUNT, value.getSavedCount());
        mutableState.setValue(value);
    }

    private static IngredientScanState restore(SavedStateHandle saved) {
        if (!ENGINE.equals(saved.get(ENGINE_KEY))) {
            return new IngredientScanState();
        }
        List<Bundle> savedRows = saved.get(BATCH_ROWS);
        List<BatchIngredientDraft> restored = new ArrayList<>();
        if (savedRows != null) {
            for (Bundle row : savedRows) {
                String category = row.getString("category");
                String selection = GroceryCategories.selection(category == null ? FOOD_CATEGORY : category);
                long date = row.getLong("expiry_date");
                restored.add(new BatchIngredientDraft(Objects.requireNonNull(row.getString("id")), row.getString("name", ""),
                        selection, row.getInt("quantity", 1), date > 0L ? date : null,
                        row.getBoolean("selected"), row.getBoolean("quantity_detected"), row.getBoolean("manual_expiry"),
                        row.getBoolean("manual_item"), row.getInt("threshold",
                                GroceryCategories.defaultThreshold(category == null ? "" : category))));
            }
        }
        Integer count = saved.get(SAVED_COUNT);
        return new IngredientScanState(false, Boolean.TRUE.equals(saved.get(REVIEWING)), restored,
                false, count == null ? 0 : count, null);
    }

    private static String scanError(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return error.getMessage() != null ? error.getMessage() : "Choose a readable item photo.";
        }
        if (error instanceof HttpException) {
            switch (((HttpException) error).code()) {
                case 503:
                    return "Visual recognition is not configured on the server. You can add items manually.";
                case 429:
                    return "Visual recognition is busy or its quota is exhausted. Try again later or add items manually.";
                case 404:
                    return "Visual recognition is unavailable on this server. Start the updated ShelfWise backend.";
                default:
                    break;
            }
        }
        if (error instanceof IOException) {
            return "Couldn’t reach visual recognition. Check your connection and that the ShelfWise backend is running.";
        }
        return "Couldn’t recognize items in this photo. Try again or add items manually.";
    }
}

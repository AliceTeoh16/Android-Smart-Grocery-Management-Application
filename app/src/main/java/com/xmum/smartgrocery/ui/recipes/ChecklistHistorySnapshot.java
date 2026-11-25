package com.xmum.smartgrocery.ui.recipes;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem;
import com.xmum.smartgrocery.data.repository.RecipeRepository.DeductedInventoryItem;
import com.xmum.smartgrocery.data.repository.RecipeRepository.MissingChecklistItem;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import kotlin.text.StringsKt;

/** Stores and validates a complete, versioned checklist snapshot in Room. */
public final class ChecklistHistorySnapshot {
    private static final Gson GSON = new Gson();
    private static final Type MISSING_ITEMS = new TypeToken<List<MissingChecklistItem>>() {}.getType();
    private static final Type COVERED_ITEMS = new TypeToken<List<DeductedInventoryItem>>() {}.getType();

    private ChecklistHistorySnapshot() {
    }

    @NonNull
    public static String newId(long timestamp) {
        return "meal_" + timestamp + "_" + UUID.randomUUID();
    }

    @NonNull
    public static ChecklistHistoryItem encode(@NonNull SavedChecklist checklist) {
        return new ChecklistHistoryItem(checklist.getId(), checklist.getDishName(), checklist.getServings(),
                checklist.getStatus(), checklist.getCreatedAt(), GSON.toJson(checklist.getMissingChecklist()),
                GSON.toJson(checklist.getInventoryDeducted()), GSON.toJson(orEmpty(checklist.getAddedShoppingItemKeys())),
                GSON.toJson(new Payload(checklist)), "");
    }

    @NonNull
    public static SavedChecklist decode(@NonNull ChecklistHistoryItem item) {
        return decode(item, null);
    }

    @NonNull
    public static SavedChecklist decode(@NonNull ChecklistHistoryItem item, @Nullable SavedChecklist legacy) {
        String[] storedKeys = fromJson(item.getAddedShoppingKeysJson(), String[].class);
        List<String> keys = storedKeys == null ? Collections.emptyList() : Arrays.asList(storedKeys);
        List<MissingChecklistItem> missing = orEmpty(fromJson(item.getMissingChecklistJson(), MISSING_ITEMS));
        List<DeductedInventoryItem> covered = orEmpty(fromJson(item.getInventoryDeductedJson(), COVERED_ITEMS));

        SavedChecklist columns = new SavedChecklist(item.getId(), item.getDishName(), item.getServings(), item.getStatus(),
                item.getCreatedAt(), missing, covered, keys, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null);
        if (StringsKt.isBlank(item.getStateJson())) {
            return withLegacyDetails(columns, legacy);
        }
        Payload payload = fromJson(item.getStateJson(), Payload.class);
        if (payload != null && payload.version == 1 && matches(columns, payload.checklist)) {
            SavedChecklist saved = payload.checklist;
            return saved.copy(saved.getId(), saved.getDishName(), saved.getServings(), item.getStatus(),
                    saved.getCreatedAt(), saved.getMissingChecklist(), saved.getInventoryDeducted(), keys,
                    saved.getIngredients(), saved.getSteps(), saved.getUncheckedInventoryItemKeys(),
                    saved.getUncheckedMissingItemKeys(), saved.getUserPrompt());
        }
        return columns;
    }

    private static boolean matches(SavedChecklist current, SavedChecklist stored) {
        return stored != null && Objects.equals(current.getId(), stored.getId())
                && current.getCreatedAt() == stored.getCreatedAt() && current.getServings() == stored.getServings()
                && Objects.equals(current.getDishName(), stored.getDishName())
                && Objects.equals(current.getMissingChecklist(), stored.getMissingChecklist())
                && Objects.equals(current.getInventoryDeducted(), stored.getInventoryDeducted());
    }

    @NonNull
    static SavedChecklist withLegacyDetails(@NonNull SavedChecklist current, @Nullable SavedChecklist stored) {
        if (!matches(current, stored)) {
            return current;
        }
        return current.copy(current.getId(), current.getDishName(), current.getServings(), current.getStatus(),
                current.getCreatedAt(), current.getMissingChecklist(), current.getInventoryDeducted(), current.getAddedShoppingItemKeys(),
                orEmpty(stored.getIngredients()), orEmpty(stored.getSteps()),
                orEmpty(stored.getUncheckedInventoryItemKeys()), orEmpty(stored.getUncheckedMissingItemKeys()),
                stored.getUserPrompt());
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? Collections.emptyList() : value;
    }

    @Nullable
    private static <T> T fromJson(String value, Type type) {
        try {
            return GSON.fromJson(value, type);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static final class Payload {
        private final int version;
        private final SavedChecklist checklist;

        private Payload(SavedChecklist checklist) {
            this.version = 1;
            this.checklist = checklist;
        }
    }
}

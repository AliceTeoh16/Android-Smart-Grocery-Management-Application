package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.entity.GroceryItem;

import kotlin.text.StringsKt;

/** Validates the editable fields before a voice command can add inventory. */
final class VoiceInventoryDraftRules {
    private VoiceInventoryDraftRules() {
    }

    @Nullable
    static String validationError(@NonNull VoiceInventoryItemDraft draft) {
        if (StringsKt.isBlank(draft.getName())) {
            return "Enter an item name.";
        }
        Integer quantity = StringsKt.toIntOrNull(draft.getQuantity());
        if (quantity == null || quantity <= 0) {
            return "Quantity must be a whole number greater than zero.";
        }
        Double price = StringsKt.toDoubleOrNull(draft.getPrice());
        if (price == null || !Double.isFinite(price) || price < 0.0) {
            return "Enter a valid price of zero or more.";
        }
        if (!GroceryCategories.all.contains(draft.getCategory())) {
            return "Choose a category.";
        }
        Long expiryDate = draft.getExpiryDate();
        if (expiryDate != null && expiryDate <= 0L) {
            return "Choose a valid expiry date or leave it Not Set.";
        }
        Integer threshold = StringsKt.toIntOrNull(draft.getThreshold());
        if (threshold == null || threshold < 0) {
            return "Low-stock threshold must be a whole number of zero or more.";
        }
        return null;
    }

    @NonNull
    static GroceryItem toGroceryItem(@NonNull VoiceInventoryItemDraft draft) {
        String error = validationError(draft);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return new GroceryItem(0, StringsKt.trim(draft.getName()).toString(), Integer.parseInt(draft.getQuantity()),
                Double.parseDouble(draft.getPrice()), draft.getCategory(), draft.getExpiryDate(),
                Integer.parseInt(draft.getThreshold()), System.currentTimeMillis(), null, "", null,
                null, null, null, null);
    }

    @NonNull
    static VoiceInventoryItemDraft fromCommand(int index, @NonNull VoiceCommand command) {
        String name = command.getItemName();
        String category = GroceryCategories.suggest(name);
        return new VoiceInventoryItemDraft(index, name, String.valueOf(command.getQuantity()), "0.00", category,
                ExpiryEstimator.estimateExpiryTimestamp(name, category),
                String.valueOf(GroceryCategories.defaultThreshold(category)));
    }
}

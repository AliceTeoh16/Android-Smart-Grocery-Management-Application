package com.xmum.smartgrocery.ui.recipes;

import androidx.annotation.NonNull;

import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.repository.RecipeRepository.DeductedInventoryItem;
import com.xmum.smartgrocery.data.repository.RecipeRepository.MissingChecklistItem;
import com.xmum.smartgrocery.utils.GroceryCategories;
import com.xmum.smartgrocery.utils.ShoppingVoiceMerge;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import kotlin.text.StringsKt;

final class ChecklistSelection {
    private static final Pattern FULLY_COVERED = Pattern.compile("\\s*\\(Fully Covered\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ChecklistSelection() {
    }

    @NonNull
    static String inventoryKey(@NonNull DeductedInventoryItem item) {
        return "inventory|" + keyPart(item.getItem()) + "|" + keyPart(item.getUsedQuantity());
    }

    @NonNull
    static String missingKey(@NonNull MissingChecklistItem item) {
        return keyPart(item.getItem()) + "|" + keyPart(item.getNeededQuantity()) + "|" + keyPart(item.getCategory());
    }

    @NonNull
    static MissingChecklistItem asShoppingItem(@NonNull DeductedInventoryItem deducted, @NonNull List<GroceryItem> inventory) {
        String name = deducted.getItem();
        String category = null;
        for (GroceryItem item : inventory) {
            if (sameFood(item.getName(), name)) {
                category = item.getCategorySelection();
                break;
            }
        }
        String quantity = StringsKt.trim(FULLY_COVERED.matcher(deducted.getUsedQuantity()).replaceAll("")).toString();
        return new MissingChecklistItem(name, quantity, category != null ? category : GroceryCategories.suggest(name));
    }

    static boolean sameFood(@NonNull String first, @NonNull String second) {
        return ShoppingVoiceMerge.normalizedName(first).equals(ShoppingVoiceMerge.normalizedName(second));
    }

    private static String keyPart(String value) {
        return StringsKt.trim(value).toString().toLowerCase(Locale.ROOT);
    }
}

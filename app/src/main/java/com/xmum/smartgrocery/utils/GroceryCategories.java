package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import kotlin.text.StringsKt;

/** Category rules shared by forms, persistence, and recognition. */
public final class GroceryCategories {
    public static final List<String> main;
    public static final List<String> foodSubcategories;
    public static final List<String> all;

    static {
        List<String> mainLabels = new ArrayList<>();
        for (Category category : Category.values()) {
            mainLabels.add(category.getLabel());
        }
        main = Collections.unmodifiableList(mainLabels);

        List<String> subcategoryLabels = new ArrayList<>();
        for (FoodSubcategory category : FoodSubcategory.values()) {
            subcategoryLabels.add(category.getLabel());
        }
        foodSubcategories = Collections.unmodifiableList(subcategoryLabels);

        List<String> selectionLabels = new ArrayList<>(mainLabels);
        selectionLabels.addAll(subcategoryLabels);
        all = Collections.unmodifiableList(selectionLabels);
    }

    private GroceryCategories() {
    }

    @NonNull
    public static Category mainCategory(@Nullable String value) {
        switch (normalized(value)) {
            case "household essentials":
            case "household":
            case "cleaning":
                return Category.HOUSEHOLD_ESSENTIALS;
            case "personal care":
            case "toiletries":
                return Category.PERSONAL_CARE;
            default:
                return Category.FOOD_BEVERAGE;
        }
    }

    @Nullable
    public static String subcategory(@Nullable String category) {
        return subcategory(category, null);
    }

    @Nullable
    public static String subcategory(@Nullable String category, @Nullable String subcategory) {
        if (mainCategory(category) != Category.FOOD_BEVERAGE) {
            return null;
        }

        String categoryLabel = foodSubcategoryLabel(category);
        if (categoryLabel != null) {
            return categoryLabel;
        }
        String value = subcategory != null && !StringsKt.isBlank(subcategory) ? subcategory : category;
        String foodLabel = foodSubcategoryLabel(value);
        if (foodLabel != null) {
            return foodLabel;
        }

        switch (normalized(value)) {
            case "fruits & vegetables":
            case "fruit":
            case "vegetables":
                return "Produce";
            case "meat":
            case "seafood":
                return "Meat & Seafood";
            case "seasoning":
            case "seasonings":
            case "spice":
                return "Spices";
            case "beverage":
            case "drinks":
                return "Beverages";
            case "snack":
            case "snacks":
            case "sweets":
                return "Snacks & Sweets";
            case "frozen":
            case "frozen food":
                return "Frozen Foods";
            case "other":
                return "Pantry";
            default:
                return null;
        }
    }

    @NonNull
    public static String selection(@NonNull String category) {
        return selection(category, null);
    }

    @NonNull
    public static String selection(@NonNull String category, @Nullable String subcategory) {
        String leaf = subcategory(category, subcategory);
        return leaf != null ? leaf : mainCategory(category).getLabel();
    }

    public static boolean isFood(@NonNull String category) {
        return mainCategory(category) == Category.FOOD_BEVERAGE;
    }

    public static int defaultThreshold(@NonNull String category) {
        return isFood(category) ? 2 : 1;
    }

    @NonNull
    public static String suggest(@NonNull String name) {
        String known = InventoryCategoryMapping.match(name);
        return known != null ? known : "Pantry";
    }

    @NonNull
    public static String recognizedSelection(@NonNull String name, @Nullable String category) {
        return recognizedSelection(name, category, null);
    }

    /** Applies category rules to a new recognition suggestion. */
    @NonNull
    public static String recognizedSelection(@NonNull String name, @Nullable String category,
            @Nullable String subcategory) {
        String known = InventoryCategoryMapping.match(name);
        if (Category.HOUSEHOLD_ESSENTIALS.getLabel().equals(known) || Category.PERSONAL_CARE.getLabel().equals(known)) {
            return known;
        }
        Category mainCategory = mainCategory(category);
        if (mainCategory != Category.FOOD_BEVERAGE) {
            return mainCategory.getLabel();
        }
        String supplied = subcategory(category, subcategory);
        // Packaging can establish that a product is frozen even when its name doesn't say so.
        if ("Frozen Foods".equals(known) || "Frozen Foods".equals(supplied)) {
            return "Frozen Foods";
        }
        if (known != null) {
            return known;
        }
        return supplied != null ? supplied : "Pantry";
    }

    @Nullable
    private static String foodSubcategoryLabel(@Nullable String value) {
        if (value != null) {
            String trimmed = StringsKt.trim(value).toString();
            for (String label : foodSubcategories) {
                if (label.equalsIgnoreCase(trimmed)) {
                    return label;
                }
            }
        }
        return null;
    }

    private static String normalized(@Nullable String value) {
        return value == null ? "" : StringsKt.trim(value).toString().toLowerCase(Locale.ROOT);
    }
}

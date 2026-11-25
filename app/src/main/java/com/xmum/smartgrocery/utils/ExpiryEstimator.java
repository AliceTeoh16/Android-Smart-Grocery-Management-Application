package com.xmum.smartgrocery.utils;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Locale;

public final class ExpiryEstimator {
    private ExpiryEstimator() {
    }

    public static int estimateDays(@Nullable String itemName, @Nullable String category) {
        String name = orEmpty(itemName).toLowerCase(Locale.ROOT);
        String normalizedCategory = orEmpty(category).toLowerCase(Locale.ROOT);

        if (containsAny(name, "fish", "salmon", "tuna", "shrimp", "prawn", "seafood")) {
            return 2;
        }
        if (containsAny(name, "chicken", "beef", "pork", "lamb", "meat")) {
            return 3;
        }
        if (containsAny(name, "lettuce", "spinach", "kale", "bok choy", "sawi", "leafy")) {
            return 4;
        }
        if (containsAny(name, "tomato", "cucumber", "broccoli", "cabbage", "carrot")) {
            return 7;
        }
        if (containsAny(name, "potato", "onion", "garlic")) {
            return 21;
        }
        if (containsAny(name, "banana", "berry", "strawberry", "avocado", "apple", "orange")) {
            return 5;
        }
        if (containsAny(name, "milk", "yogurt")) {
            return 7;
        }
        if (name.contains("cheese")) {
            return 14;
        }
        if (name.contains("bread")) {
            return 5;
        }
        if (containsAny(name, "rice", "pasta", "noodle", "canned", "can ")) {
            return 180;
        }

        if (containsAny(normalizedCategory, "meat", "seafood")) {
            return 2;
        }
        if (containsAny(normalizedCategory, "produce", "fruit", "vegetable", "dairy")) {
            return 7;
        }
        if (normalizedCategory.contains("bakery")) {
            return 5;
        }
        if (containsAny(normalizedCategory, "pantry", "spice")) {
            return 180;
        }
        if (normalizedCategory.contains("beverage")) {
            return 14;
        }
        if (normalizedCategory.contains("snack")) {
            return 90;
        }
        return 7;
    }

    @Nullable
    public static Long estimateExpiryTimestamp(@Nullable String itemName, @Nullable String category) {
        if (!GroceryCategories.isFood(orEmpty(category))) {
            return null;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, estimateDays(itemName, category));
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis();
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}

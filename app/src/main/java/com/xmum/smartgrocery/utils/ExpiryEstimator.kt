package com.xmum.smartgrocery.utils

import java.util.Calendar
import java.util.Locale

object ExpiryEstimator {

    fun estimateDays(itemName: String?, category: String?): Int {
        val name = itemName.orEmpty().lowercase(Locale.ROOT)
        val normalizedCategory = category.orEmpty().lowercase(Locale.ROOT)

        return when {
            name.containsAny("fish", "salmon", "tuna", "shrimp", "prawn", "seafood") -> 2
            name.containsAny("chicken", "beef", "pork", "lamb", "meat") -> 3
            name.containsAny("lettuce", "spinach", "kale", "bok choy", "sawi", "leafy") -> 4
            name.containsAny("tomato", "cucumber", "broccoli", "cabbage", "carrot") -> 7
            name.containsAny("potato", "onion", "garlic") -> 21
            name.containsAny("banana", "berry", "strawberry", "avocado", "apple", "orange") -> 5
            name.containsAny("milk", "yogurt") -> 7
            name.contains("cheese") -> 14
            name.contains("bread") -> 5
            name.containsAny("rice", "pasta", "noodle", "canned", "can ") -> 180

            normalizedCategory.contains("meat") || normalizedCategory.contains("seafood") -> 2
            normalizedCategory.contains("produce") || normalizedCategory.contains("fruit") || normalizedCategory.contains("vegetable") -> 7
            normalizedCategory.contains("dairy") -> 7
            normalizedCategory.contains("bakery") -> 5
            normalizedCategory.contains("pantry") || normalizedCategory.contains("spice") -> 180
            normalizedCategory.contains("beverage") -> 14
            normalizedCategory.contains("snack") -> 90
            else -> 7
        }
    }

    fun estimateExpiryTimestamp(itemName: String?, category: String?): Long? {
        if (!GroceryCategories.isFood(category.orEmpty())) return null
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, estimateDays(itemName, category))
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { contains(it) }
    }
}

package com.xmum.smartgrocery.utils

import com.xmum.smartgrocery.data.local.entity.GroceryItem
import org.junit.Assert.*
import org.junit.Test

class InventoryScopeTest {
    @Test fun legacyFoodCategoriesNormalizeWithoutLosingDetail() {
        val item = GroceryItem(name = "Tomato", quantity = 2, price = 1.0,
            category = "Fruits & Vegetables").normalized()
        assertEquals("Food & Beverage", item.category)
        assertEquals("Produce", item.subcategory)
        assertEquals("Produce", item.categorySelection)
        assertEquals("Dairy", item.copy(category = "Dairy").normalized().subcategory)
        assertEquals("Meat & Seafood", GroceryCategories.subcategory("Meat"))
    }

    @Test fun nonFoodNeverRetainsFoodSubcategoriesOrInventsExpiry() {
        val shampoo = GroceryItem(name = "Shampoo", quantity = 1, price = 5.0,
            category = "Personal Care", subcategory = "Dairy").normalized()
        assertNull(shampoo.subcategory)
        assertNull(shampoo.expiryDate)
        assertNull(ExpiryEstimator.estimateExpiryTimestamp("Milk body wash", "Personal Care"))
        assertNull(ExpiryEstimator.estimateExpiryTimestamp("Toilet Paper", "Household Essentials"))
        assertEquals(1, GroceryCategories.defaultThreshold(shampoo.category))
        assertEquals(2, GroceryCategories.defaultThreshold("Produce"))
    }

    @Test fun missingDatesNeverExpireOrTriggerWarnings() {
        for (date in listOf(null, 0L, -1L)) {
            assertFalse(DateUtils.isExpired(date))
            assertFalse(DateUtils.isExpiringWithin(date, 7))
            assertEquals("Not Set", DateUtils.formatDate(date))
        }
        assertTrue(DateUtils.isExpired(DateUtils.getStartOfToday() - 1L))
        assertTrue(DateUtils.isExpiringWithin(DateUtils.getEndOfToday(), 3))
        assertFalse(DateUtils.isExpiringWithin(DateUtils.getEndOfToday() + 20 * 86400000L, 3))
    }

    @Test fun mixedProductsHaveSensibleSuggestionsAndDistinctColors() {
        assertEquals("Personal Care", GroceryCategories.suggest("Shampoo"))
        assertEquals("Household Essentials", GroceryCategories.suggest("Dishwashing detergent"))
        assertEquals("Household Essentials", GroceryCategories.suggest("Toilet paper"))
        assertEquals("Produce", GroceryCategories.suggest("Tomato"))
        assertEquals("Spices", GroceryCategories.suggest("Cumin"))
        assertEquals(3, Category.entries.map { it.background }.distinct().size)
        assertEquals(3, Category.entries.map { it.foreground }.distinct().size)
        assertEquals(9, GroceryCategories.foodSubcategories.size)
    }

    @Test fun voiceReviewCanSaveAnUndatedNonFoodItem() {
        val draft = VoiceInventoryItemDraft.fromCommand(0, VoiceCommand(VoiceAction.ADD, 2, "Shampoo"))
        assertNull(draft.validationError)
        assertNull(draft.expiryDate)
        assertEquals("Personal Care", draft.toGroceryItem().normalized().category)
    }

    @Test fun expandedFoodGroupsNormalizeAndSuggestedProductsKeepTheirSpecificCategory() {
        assertEquals(listOf("Produce", "Meat & Seafood", "Dairy", "Bakery", "Pantry", "Spices",
            "Beverages", "Snacks & Sweets", "Frozen Foods"), GroceryCategories.foodSubcategories)
        assertEquals("Beverages", GroceryCategories.selection("Beverages"))
        assertEquals("Snacks & Sweets", GroceryCategories.selection("Snacks"))
        for ((category, names) in listOf(
            "Beverages" to listOf("Water", "Coffee", "Tea", "Juices"),
            "Snacks & Sweets" to listOf("Chips", "Biscuits", "Chocolates", "Nuts"),
            "Frozen Foods" to listOf("Frozen dumplings", "Ice cream", "Frozen meat"))) {
            for (name in names) {
                assertEquals(name, category, GroceryCategories.suggest(name))
                val item = GroceryItem(name = name, quantity = 1, price = 2.0, category = category).normalized()
                assertEquals("Food & Beverage", item.category)
                assertEquals(category, item.subcategory)
                assertEquals(category, item.categorySelection)
            }
        }
        assertEquals("Pantry", GroceryItem(name = "Coffee", quantity = 1, price = 2.0,
            category = "Food & Beverage", subcategory = "Pantry").normalized().subcategory)
    }
}

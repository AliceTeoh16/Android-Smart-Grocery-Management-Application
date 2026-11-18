package com.xmum.smartgrocery.utils

enum class Category(val label: String, val background: Long, val foreground: Long) {
    FOOD_BEVERAGE("Food & Beverage", 0xFFE4F3E8, 0xFF286343),
    HOUSEHOLD_ESSENTIALS("Household Essentials", 0xFFE2EEFF, 0xFF315D99),
    PERSONAL_CARE("Personal Care", 0xFFF2E6FA, 0xFF804A99)
}

enum class FoodSubcategory(val label: String) {
    PRODUCE("Produce"), MEAT_SEAFOOD("Meat & Seafood"), DAIRY("Dairy"),
    BAKERY("Bakery"), PANTRY("Pantry"), SPICES("Spices"),
    BEVERAGES("Beverages"), SNACKS_SWEETS("Snacks & Sweets"), FROZEN_FOODS("Frozen Foods")
}

/** One taxonomy shared by persistence, forms, recognition and category colors. */
object GroceryCategories {
    val main = Category.entries.map { it.label }
    val foodSubcategories = FoodSubcategory.entries.map { it.label }
    // Form selections may be a food leaf; persistence splits it into category + subcategory.
    val all = main + foodSubcategories

    fun mainCategory(value: String?): Category = when (value?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "household essentials", "household", "cleaning" -> Category.HOUSEHOLD_ESSENTIALS
        "personal care", "toiletries" -> Category.PERSONAL_CARE
        else -> Category.FOOD_BEVERAGE
    }

    fun subcategory(category: String?, subcategory: String? = null): String? {
        if (mainCategory(category) != Category.FOOD_BEVERAGE) return null
        val value = category?.trim()?.takeIf { candidate -> foodSubcategories.any { it.equals(candidate, true) } }
            ?: subcategory?.takeIf { it.isNotBlank() } ?: category
        return foodSubcategories.firstOrNull { it.equals(value?.trim(), true) } ?: when (value?.trim()?.lowercase(java.util.Locale.ROOT)) {
            "fruits & vegetables", "fruit", "vegetables" -> "Produce"
            "meat", "seafood" -> "Meat & Seafood"
            "seasoning", "seasonings", "spice" -> "Spices"
            "beverage", "drinks" -> "Beverages"
            "snack", "snacks", "sweets" -> "Snacks & Sweets"
            "frozen", "frozen food" -> "Frozen Foods"
            "other" -> "Pantry"
            else -> null
        }
    }

    fun selection(category: String, subcategory: String? = null): String =
        subcategory(category, subcategory) ?: mainCategory(category).label

    fun isFood(category: String) = mainCategory(category) == Category.FOOD_BEVERAGE
    fun defaultThreshold(category: String) = if (isFood(category)) 2 else 1

    fun suggest(name: String): String = InventoryCategoryMapping.match(name) ?: "Pantry"

    /** Apply only to new recognition suggestions, never to saved rows or user edits. */
    fun recognizedSelection(name: String, category: String?, subcategory: String? = null): String {
        val known = InventoryCategoryMapping.match(name)
        if (known in main.drop(1)) return known!!
        val mainCategory = mainCategory(category)
        if (mainCategory != Category.FOOD_BEVERAGE) return mainCategory.label
        val supplied = subcategory(category, subcategory)
        // Packaging may establish that a product is frozen even without "frozen" in its name.
        if (known == "Frozen Foods" || supplied == "Frozen Foods") return "Frozen Foods"
        return known ?: supplied ?: "Pantry"
    }
}

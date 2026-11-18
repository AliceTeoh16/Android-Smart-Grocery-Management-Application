package com.xmum.smartgrocery.utils

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class InventoryInputCategoryMappingTest {
    private data class Case(val name: String, val selection: String)
    private val cases = javaClass.getResourceAsStream("/inventory-category-cases.json")!!.reader().use {
        Gson().fromJson(it, Array<Case>::class.java).toList()
    }

    @Test fun unknownProductNamesKeepTheRecognizedCategoryInsteadOfRequiringKeywordMatches() {
        data class Unlisted(val name: String, val category: String, val subcategory: String?)
        val products = javaClass.getResourceAsStream("/unlisted-recognition-cases.json")!!.reader().use {
            Gson().fromJson(it, Array<Unlisted>::class.java).toList()
        }
        for (product in products) {
            assertNull("Must exercise an unlisted name: ${product.name}", InventoryCategoryMapping.match(product.name))
            assertEquals(product.name, product.subcategory ?: product.category,
                GroceryCategories.recognizedSelection(product.name, product.category, product.subcategory))
        }
    }

    @Test fun voiceAndPhotoSuggestionsAgreeAcrossAllFoodGroupsAndNonFoodProducts() {
        assertEquals(GroceryCategories.foodSubcategories.toSet() + GroceryCategories.main.drop(1), cases.map { it.selection }.toSet())
        for (case in cases) {
            assertEquals("Voice: ${case.name}", case.selection, GroceryCategories.suggest(case.name))
            assertEquals("Photo: ${case.name}", case.selection,
                GroceryCategories.recognizedSelection(case.name, "Food & Beverage", "Pantry"))
            assertEquals("Missing photo label: ${case.name}", case.selection,
                GroceryCategories.recognizedSelection(case.name, null))
            val command = VoiceCommand(VoiceAction.ADD, 2, case.name)
            val draft = VoiceInventoryItemDraft.fromCommand(0, command)
            assertNull(draft.validationError)
            assertEquals(case.selection, draft.category)
            val item = draft.toGroceryItem().normalized()
            val shopping = ShoppingVoiceMerge.merge(emptyList(), command, "Household").normalized()
            assertEquals(item.category, shopping.category)
            assertEquals(item.subcategory, shopping.subcategory)
            if (case.selection in GroceryCategories.main.drop(1)) {
                assertNull(item.subcategory)
                assertNull(draft.expiryDate)
                assertEquals("1", draft.threshold)
            } else {
                assertEquals("Food & Beverage", item.category)
                assertEquals(case.selection, item.subcategory)
                assertEquals("2", draft.threshold)
            }
        }
    }

    @Test fun parsedVoiceCommandsKeepNamesCountsAndTheRightCategoryInEveryLanguage() {
        for ((text, expected) in listOf(
            "add 2 bottles of shampoo, 1 toilet paper, 3 orange juice, 2 cream crackers, 1 frozen chicken" to
                listOf("Personal Care", "Household Essentials", "Beverages", "Snacks & Sweets", "Frozen Foods"),
            "tambah dua botol syampu dan satu tisu tandas dan tiga botol jus oren dan dua biskut dan satu pek ladu beku" to
                listOf("Personal Care", "Household Essentials", "Beverages", "Snacks & Sweets", "Frozen Foods"),
            "添加两瓶洗发水和一包卫生纸和三瓶果汁和两包饼干和一包冷冻饺子" to
                listOf("Personal Care", "Household Essentials", "Beverages", "Snacks & Sweets", "Frozen Foods"))) {
            val commands = VoiceParser.parseAll(text)
            assertTrue(text, commands.all { it.action == VoiceAction.ADD })
            assertEquals(text, listOf(2, 1, 3, 2, 1), commands.map { it.quantity })
            assertEquals(text, expected, commands.mapIndexed { index, command -> VoiceInventoryItemDraft.fromCommand(index, command).category })
        }
    }

    @Test fun photoFallbacksRespectVisualContextAndManualChoicesAreNotReclassified() {
        assertEquals("Frozen Foods", GroceryCategories.recognizedSelection("Chicken", "Food & Beverage", "Frozen Foods"))
        assertEquals("Produce", GroceryCategories.recognizedSelection("Dragonfruit", "Food & Beverage", " produce "))
        assertEquals("Personal Care", GroceryCategories.recognizedSelection("Unfamiliar product", "toiletries", "Dairy"))
        assertEquals("Household Essentials", GroceryCategories.recognizedSelection("Unfamiliar product", "Household Essentials", "Pantry"))
        assertEquals("Snacks & Sweets", GroceryCategories.recognizedSelection("Unfamiliar product", "Snacks"))
        assertEquals("Pantry", GroceryCategories.recognizedSelection("Unfamiliar product", "Invalid"))
        val manual = com.xmum.smartgrocery.data.local.entity.GroceryItem(name = "Coffee", quantity = 1,
            price = 0.0, category = "Food & Beverage", subcategory = "Pantry").normalized()
        assertEquals("Pantry", manual.subcategory)
    }

    @Test fun aTeacupIsAHouseholdObjectAndTeaIsABeverageForVoiceAndPhoto() {
        val commands = VoiceParser.parseAll("add two tea cups and one cup of tea")
        assertEquals(listOf(2, 1), commands.map { it.quantity })
        assertEquals(listOf("Household Essentials", "Beverages"), commands.map { GroceryCategories.suggest(it.itemName) })
        val cup = VoiceInventoryItemDraft.fromCommand(0, commands.first())
        assertNull(cup.expiryDate)
        assertEquals("1", cup.threshold)
        assertEquals("Household Essentials", GroceryCategories.recognizedSelection("Tea cup", "Food & Beverage", "Beverages"))
        assertEquals("Beverages", GroceryCategories.recognizedSelection("Cup of tea", "Food & Beverage", "Beverages"))
    }
}

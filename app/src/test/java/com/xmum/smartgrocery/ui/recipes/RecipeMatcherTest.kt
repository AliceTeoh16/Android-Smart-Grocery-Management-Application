package com.xmum.smartgrocery.ui.recipes

import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.Recipe
import com.xmum.smartgrocery.data.local.entity.RecipeIngredient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMatcherTest {

    private val sampleRecipe = Recipe(
        id = 99,
        name = "Tomato Scrambled Eggs",
        ingredients = listOf(
            RecipeIngredient("Egg", 3.0, "pcs"),
            RecipeIngredient("Tomato", 2.0, "pcs"),
            RecipeIngredient("Scallion", 1.0, "stalk")
        ),
        instructions = emptyList(),
        imageUrl = "",
        videoUrl = ""
    )

    @Test
    fun matchRecipe_allIngredientsOwned_returns100Percent() {
        val inventory = listOf(
            GroceryItem(id = 1, name = "Fresh Eggs", quantity = 10, price = 0.2, category = "Dairy", expiryDate = 0),
            GroceryItem(id = 2, name = "Organic Tomato", quantity = 3, price = 0.5, category = "Produce", expiryDate = 0),
            GroceryItem(id = 3, name = "Chopped Scallions", quantity = 1, price = 0.1, category = "Produce", expiryDate = 0)
        )

        val missingList = mutableListOf<String>()
        var ownedCount = 0

        sampleRecipe.ingredients.forEach { req ->
            val hasIngredient = inventory.any { invItem ->
                invItem.quantity > 0 && (
                    invItem.name.contains(req.name, ignoreCase = true) || 
                    req.name.contains(invItem.name, ignoreCase = true)
                )
            }
            if (hasIngredient) {
                ownedCount++
            } else {
                missingList.add(req.name)
            }
        }

        val matchPercent = ((ownedCount.toDouble() / sampleRecipe.ingredients.size.toDouble()) * 100).toInt()

        assertEquals(3, ownedCount)
        assertEquals(0, missingList.size)
        assertEquals(100, matchPercent)
    }

    @Test
    fun matchRecipe_partialIngredientsOwned_returnsCorrectPercentage() {
        val inventory = listOf(
            GroceryItem(id = 1, name = "Eggs", quantity = 4, price = 0.2, category = "Dairy", expiryDate = 0)
            // Tomatoes and scallions are missing
        )

        val missingList = mutableListOf<String>()
        var ownedCount = 0

        sampleRecipe.ingredients.forEach { req ->
            val hasIngredient = inventory.any { invItem ->
                invItem.quantity > 0 && (
                    invItem.name.contains(req.name, ignoreCase = true) || 
                    req.name.contains(invItem.name, ignoreCase = true)
                )
            }
            if (hasIngredient) {
                ownedCount++
            } else {
                missingList.add(req.name)
            }
        }

        val matchPercent = ((ownedCount.toDouble() / sampleRecipe.ingredients.size.toDouble()) * 100).toInt()

        assertEquals(1, ownedCount)
        assertEquals(2, missingList.size)
        assertTrue(missingList.contains("Tomato"))
        assertTrue(missingList.contains("Scallion"))
        assertEquals(33, matchPercent) // 1/3 is 33%
    }
}

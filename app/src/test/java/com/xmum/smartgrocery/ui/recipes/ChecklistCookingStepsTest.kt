package com.xmum.smartgrocery.ui.recipes

import com.google.gson.Gson
import com.xmum.smartgrocery.data.network.AiApiService
import com.xmum.smartgrocery.data.network.dto.RecipeRequest
import com.xmum.smartgrocery.data.network.dto.RecipeResponse
import com.xmum.smartgrocery.data.repository.RecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChecklistCookingStepsTest {
    private val gson = Gson()
    private val payload = """{"dish_name":"Cheesecake","servings":2,
        "missing_checklist":[{"name":"Cream Cheese","missing_qty":"400g","category":"Dairy"}],
        "inventory_deducted":[{"name":"Eggs","qty_used":"2 pcs (Fully Covered)"}],
        "ingredients":[{"name":"Cream Cheese","amount":"400g","is_in_inventory":false},{"name":"Eggs","amount":"2 pcs","is_in_inventory":true}],
        "steps":["Beat the cream cheese until smooth.","Beat in the eggs one at a time."]}"""
    private fun repository(response: RecipeResponse) = RecipeRepository(object : AiApiService {
        override suspend fun generateRecipes(request: RecipeRequest): RecipeResponse = response
    })
    @Test fun aSingleResponseMapsStepsWithoutChangingShoppingOrDeductionQuantities() = runBlocking {
        val result = repository(gson.fromJson(payload, RecipeResponse::class.java)).generateChecklistAssistantResult(emptyList(), "Cheesecake for 2")
        assertEquals("Cheesecake", result.dishName); assertEquals(2, result.servings)
        assertEquals("400g", result.missingChecklist.single().neededQuantity)
        assertEquals("2 pcs (Fully Covered)", result.inventoryDeducted.single().usedQuantity)
        assertEquals("Eggs 2 pcs (Fully Covered)", result.alreadyHave.single())
        assertEquals("400g", result.ingredients.first().amount)
        assertFalse(result.ingredients.first().isInInventory); assertTrue(result.ingredients.last().isInInventory)
        assertEquals("Beat in the eggs one at a time.", result.steps.last())
    }
    @Test fun legacyResponsesStayReadableWithoutInventingInstructions() = runBlocking {
        val legacy = gson.fromJson("""{"dish_name":"Old recipe","servings":2,"missing_checklist":[],"inventory_deducted":[]}""", RecipeResponse::class.java)
        val result = repository(legacy).generateChecklistAssistantResult(emptyList(), "Old recipe")
        assertTrue(result.steps.isEmpty()); assertTrue(result.ingredients.isEmpty())
    }
    @Test fun partialNewRecipeIsRejectedInsteadOfShowingMismatchedInstructions() = runBlocking {
        val data = gson.fromJson(payload, RecipeResponse::class.java).copy(steps = emptyList())
        assertTrue(runCatching { repository(data).generateChecklistAssistantResult(emptyList(), "Cheesecake") }.isFailure)
    }
    private fun saved() = SavedChecklist("meal-1", "Cheesecake", 2, "pending", 1L,
        listOf(RecipeRepository.MissingChecklistItem("Cream Cheese", "400g", "Dairy")),
        listOf(RecipeRepository.DeductedInventoryItem("Eggs", "2 pcs")),
        ingredients = listOf(RecipeRepository.ChecklistIngredient("Cream Cheese", "400g", false)), steps = listOf("Beat the cream cheese."))
    @Test fun savedMetadataRoundTripsAndSurvivesRoomStatusAndShoppingKeyRefresh() {
        val original = gson.fromJson(gson.toJson(saved()), SavedChecklist::class.java)
        val room = original.copy(status = "cooked", addedShoppingItemKeys = listOf("cheese"), ingredients = null, steps = null)
        val restored = room.withCookingDetailsFrom(original)
        assertEquals(original.steps, restored.steps); assertEquals(original.ingredients, restored.ingredients)
        assertEquals("cooked", restored.status); assertEquals(listOf("cheese"), restored.addedShoppingItemKeys)
    }
    @Test fun changedIngredientPlanCannotInheritStepsFromAnOlderRecipe() {
        val original = saved()
        val changed = original.copy(missingChecklist = emptyList(), ingredients = null, steps = null)
        assertNull(changed.withCookingDetailsFrom(original).steps)
        assertNull(original.copy(id = "different", steps = null).withCookingDetailsFrom(original).steps)
    }
    @Test fun oldSavedJsonHasNoStepsAndDoesNotCrashWhenMerged() {
        val legacy = gson.fromJson("""{"id":"old","dishName":"Eggs","servings":2,"status":"pending","createdAt":1,"missingChecklist":[],"inventoryDeducted":[]}""", SavedChecklist::class.java)
        assertTrue(legacy.withCookingDetailsFrom(legacy).steps.orEmpty().isEmpty())
    }
}

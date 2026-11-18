package com.xmum.smartgrocery.ui.recipes

import com.xmum.smartgrocery.data.repository.RecipeRepository
import org.junit.Assert.*
import org.junit.Test

class ChecklistHistorySnapshotTest {
    private fun saved() = SavedChecklist(ChecklistHistorySnapshot.newId(123L), "Beef Noodle", 2, "pending", 123L,
        listOf(RecipeRepository.MissingChecklistItem("Noodles / 面", "200 g", "Pantry")),
        listOf(RecipeRepository.DeductedInventoryItem("Beef", "150g (Fully Covered)")),
        ingredients = listOf(RecipeRepository.ChecklistIngredient("Noodles / 面", "200 g", false),
            RecipeRepository.ChecklistIngredient("Beef", "150g", true)),
        steps = listOf("Cook noodles.", "Add beef and serve."),
        uncheckedInventoryItemKeys = listOf("inventory|beef|150g (fully covered)"),
        uncheckedMissingItemKeys = listOf("noodles / 面|200 g|pantry"), userPrompt = "Beef noodles for two")

    @Test fun completePayloadAndBothCheckboxChoicesRoundTripInRoomRecord() {
        val saved = saved()
        assertEquals(saved, ChecklistHistorySnapshot.decode(ChecklistHistorySnapshot.encode(saved)))
    }
    @Test fun sameDishAndSameTimestampStillHaveIndependentIdentities() {
        val a = saved(); val b = saved()
        assertNotEquals(a.id, b.id)
        assertTrue(a.id.startsWith("meal_123_"))
        assertEquals(a.dishName, b.dishName)
    }
    @Test fun authoritativeRoomStatusAndShoppingMarkersOverrideSnapshotStatus() {
        val saved = saved()
        val row = ChecklistHistorySnapshot.encode(saved).copy(status = "cooked", addedShoppingKeysJson = "[\"noodles\"]")
        val restored = ChecklistHistorySnapshot.decode(row)
        assertEquals("cooked", restored.status)
        assertEquals(listOf("noodles"), restored.addedShoppingItemKeys)
        assertEquals(saved.steps, restored.steps)
        assertEquals(saved.uncheckedInventoryItemKeys, restored.uncheckedInventoryItemKeys)
    }
    @Test fun databaseSnapshotAlwaysWinsOverStalePreferencePayload() {
        val saved = saved()
        assertEquals(saved, ChecklistHistorySnapshot.decode(ChecklistHistorySnapshot.encode(saved), saved.copy(steps = listOf("Stale steps"))))
    }
    @Test fun legacyBackfillRequiresSameIdentityAndIngredientPlan() {
        val saved = saved(); val legacy = ChecklistHistorySnapshot.encode(saved).copy(stateJson = "")
        assertEquals(saved, ChecklistHistorySnapshot.decode(legacy, saved))
        assertTrue(ChecklistHistorySnapshot.decode(legacy, saved.copy(id = "other")).steps.orEmpty().isEmpty())
        assertTrue(ChecklistHistorySnapshot.decode(legacy, saved.copy(missingChecklist = emptyList())).steps.orEmpty().isEmpty())
    }
    @Test fun damagedSnapshotFallsBackToStoredIngredientsWithoutGuessingStepsOrUsingPreferences() {
        val saved = saved()
        val row = ChecklistHistorySnapshot.encode(saved).copy(stateJson = "invalid json")
        val restored = ChecklistHistorySnapshot.decode(row, saved)
        assertEquals(saved.missingChecklist, restored.missingChecklist)
        assertEquals(saved.inventoryDeducted, restored.inventoryDeducted)
        assertTrue(restored.steps.orEmpty().isEmpty())
    }
}

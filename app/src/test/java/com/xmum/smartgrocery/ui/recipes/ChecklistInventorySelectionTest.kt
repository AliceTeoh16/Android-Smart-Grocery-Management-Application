package com.xmum.smartgrocery.ui.recipes

import com.google.gson.Gson
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.repository.RecipeRepository
import org.junit.Assert.*
import org.junit.Test

class ChecklistInventorySelectionTest {
    private val egg = RecipeRepository.DeductedInventoryItem("Eggs (鸡蛋)", "2 pcs (Fully Covered)")
    private val milk = RecipeRepository.MissingChecklistItem("Milk", "1 bottle", "Dairy")
    private val stock = listOf(GroceryItem(1, "Egg", 5, 2.0, "Food & Beverage", subcategory = "Dairy"))
    private fun state(selected: Boolean = true, added: Boolean = false) = ChecklistUiState(availableIngredients = stock,
        selectableMissingItems = listOf(SelectableChecklistItem(milk)),
        selectableInventoryItems = listOf(SelectableInventoryChecklistItem(egg, selected, added)))

    @Test fun shoppingIncludesCheckedInventoryAndMissingItemsAndStripsOnlyTheCoverageTag() {
        val state = state()
        assertEquals(2, state.selectedShoppingCount)
        val owned = state.selectedShoppingItems.last()
        assertEquals("Eggs (鸡蛋)", owned.item); assertEquals("2 pcs", owned.neededQuantity); assertEquals("Dairy", owned.category)
        assertEquals("2 pcs (Fully Covered)", egg.usedQuantity)
    }
    @Test fun onlyUncheckedInventoryIsExcludedAndPendingRowsCanBeAddedAgain() {
        assertEquals(1, state(selected = false).selectedShoppingCount)
        val added = state(added = true)
        assertEquals(2, added.selectedShoppingCount)
        assertTrue(added.selectableInventoryItems.single().isSelected)
    }
    @Test fun warningTracksOnlyItemsInTheCurrentShoppingSelectionIncludingPartialStock() {
        assertTrue(state().hasSelectedItemsInInventory)
        assertFalse(state(selected = false).hasSelectedItemsInInventory)
        assertTrue(state(added = true).hasSelectedItemsInInventory)
        val partial = state(selected = false).copy(selectableMissingItems = listOf(SelectableChecklistItem(
            RecipeRepository.MissingChecklistItem("Egg", "1 pcs", "Dairy"))))
        assertTrue(partial.hasSelectedItemsInInventory)
    }
    @Test fun stockOnlyChecklistsCanSelectShoppingItemsAndDistinctFoodsDoNotMatch() {
        assertEquals(1, state().copy(selectableMissingItems = emptyList()).selectedShoppingCount)
        assertFalse(sameChecklistFood("Egg", "Eggplant"))
        assertFalse(sameChecklistFood("Milk", "Coconut Milk"))
    }
    @Test fun uncheckedConsumptionMetadataSurvivesJsonAndRoomRefreshWithoutChangingIngredientAmounts() {
        val saved = SavedChecklist("meal", "Eggs", 2, "pending", 1, emptyList(), listOf(egg),
            uncheckedInventoryItemKeys = listOf(egg.inventorySelectionKey()))
        val cached = Gson().fromJson(Gson().toJson(saved), SavedChecklist::class.java)
        val room = saved.copy(uncheckedInventoryItemKeys = null)
        assertEquals(saved.uncheckedInventoryItemKeys, room.withCookingDetailsFrom(cached).uncheckedInventoryItemKeys)
        assertEquals(saved.inventoryDeducted, room.withCookingDetailsFrom(cached).inventoryDeducted)
        assertNull(room.copy(dishName = "Other meal").withCookingDetailsFrom(cached).uncheckedInventoryItemKeys)
    }
    @Test fun legacySavedChecklistsDefaultToAllInventorySelectedAndKeysCannotCollideWithMissingRows() {
        val old = Gson().fromJson("""{"id":"meal","dishName":"Eggs","servings":2,"status":"pending","createdAt":1,"missingChecklist":[],"inventoryDeducted":[]}""", SavedChecklist::class.java)
        assertTrue(old.uncheckedInventoryItemKeys.orEmpty().isEmpty())
        assertTrue(egg.inventorySelectionKey().startsWith("inventory|"))
    }
}

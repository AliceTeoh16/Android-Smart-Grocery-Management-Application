package com.xmum.smartgrocery.ui.recipes

import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.repository.RecipeRepository
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses RAM inventory and restores the existing saved-checklist preference even after test failures. */
class ChecklistInventorySelectionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val database get() = (instrumentation as VoiceCommandTestRunner).database
    private val gson = Gson()
    private val warning = "* Note: Some selected items are already in stock."
    private val covered = listOf(RecipeRepository.DeductedInventoryItem("Eggs", "2 pcs (Fully Covered)"),
        RecipeRepository.DeductedInventoryItem("Tomato", "1 pcs (Fully Covered)"))
    private val missing = listOf(RecipeRepository.MissingChecklistItem("Milk", "1 bottle", "Dairy"))
    private val checklist = SavedChecklist("inventory-selection-test", "Breakfast", 2, "pending", System.currentTimeMillis(), missing, covered)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var eggs = 0
    private var tomato = 0
    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { it.openPantryAction(PantryAction.AiChecklist) }
        visit("Use Eggs")
    }
    private fun fixture(test: suspend () -> Unit): Unit = runBlocking {
        val prefs = instrumentation.targetContext.getSharedPreferences("ai_checklist_store", Context.MODE_PRIVATE)
        val original = prefs.getString("saved_checklists", null)
        val originalPointer = prefs.getString("active_history_id", null)
        val originalMarker = prefs.getBoolean("room_history_snapshot_migrated", false)
        try {
            database.clearAllTables()
            eggs = database.groceryDao().insertWithLog(GroceryItem(name = "Eggs", quantity = 5, price = 2.0,
                category = "Dairy", expiryDate = 1900000000000L, threshold = 0)).toInt()
            tomato = database.groceryDao().insertWithLog(GroceryItem(name = "Tomato", quantity = 4, price = 2.0,
                category = "Produce", expiryDate = 1900000000000L, threshold = 0)).toInt()
            database.checklistHistoryDao().insert(ChecklistHistoryItem(checklist.id, checklist.dishName, checklist.servings,
                checklist.status, checklist.createdAt, gson.toJson(missing), gson.toJson(covered)))
            assertTrue(prefs.edit().putString("saved_checklists", gson.toJson(listOf(checklist))).commit())
            open(); test()
        } finally {
            scenario?.close(); scenario = null
            val edit = prefs.edit()
            if (original == null) edit.remove("saved_checklists") else edit.putString("saved_checklists", original)
            if (originalPointer == null) edit.remove("active_history_id") else edit.putString("active_history_id", originalPointer)
            edit.putBoolean("room_history_snapshot_migrated", originalMarker)
            assertTrue(edit.commit())
        }
    }
    @Test fun personalSelectionWarnsAndAddsStockWithoutDeductingOrLockingConsumptionCheckboxes() = fixture {
        visit("(Fully Covered)"); visit("In stock")
        click("Add Selected (3) to Shopping List")
        visit(warning); visit("Personal Shopping List"); visit("Household / Shared Shopping List"); capture("warning")
        click("Personal Shopping List")
        eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == 3 } }
        val rows = database.shoppingListDao().getShoppingListItems().first()
        assertTrue(rows.all { it.assignedTo == "Me" })
        assertEquals(2, rows.single { it.itemName == "Eggs" }.quantityNeeded)
        assertEquals("2 pcs", rows.single { it.itemName == "Eggs" }.quantityLabel)
        click("Use Eggs"); eventually { !state().selectableInventoryItems.first().isSelected }
        click("Use Eggs"); eventually { state().selectableInventoryItems.first().isSelected }
        assertEquals(2, state().selectedShoppingCount) // Added pantry rows remain selectable for another addition.
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
        assertEquals(4, database.groceryDao().getItemById(tomato)!!.quantity)
        capture("checkboxes")
    }
    @Test fun householdSelectionAndReopenPreserveUncheckedStockAndCookOnlyCheckedIngredients() = fixture {
        click("Use Eggs"); eventually { !state().selectableInventoryItems.first().isSelected }
        click("Add Selected (2) to Shopping List"); visit(warning); click("Household / Shared Shopping List")
        eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == 2 } }
        val rows = database.shoppingListDao().getShoppingListItems().first()
        assertTrue(rows.all { it.assignedTo == "Household" }); assertFalse(rows.any { it.itemName == "Eggs" })
        scenario!!.recreate(); visit("Use Eggs")
        eventually { !state().selectableInventoryItems.first().isSelected }
        scenario!!.close(); scenario = null; open()
        eventually { !state().selectableInventoryItems.first().isSelected && state().selectableInventoryItems.last().isSelected }
        click("Mark as cooked")
        eventually { runBlocking { database.checklistHistoryDao().getChecklistHistoryById(checklist.id)!!.status == "cooked" } }
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
        assertEquals(3, database.groceryDao().getItemById(tomato)!!.quantity)
        assertEquals(listOf("ADD"), database.groceryDao().getLogsForItem(eggs).first().map { it.changeType })
    }
    @Test fun missingOnlySelectionHasBothTargetsWithoutWarningAndNoUncheckedStockIsConsumed() = fixture {
        click("Use Eggs"); click("Use Tomato")
        eventually { state().selectableInventoryItems.none { it.isSelected } }
        click("Add Selected (1) to Shopping List"); visit("Personal Shopping List"); visit("Household / Shared Shopping List")
        assertNull(find(warning))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        click("Mark as cooked")
        eventually { runBlocking { database.checklistHistoryDao().getChecklistHistoryById(checklist.id)!!.status == "cooked" } }
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
        assertEquals(4, database.groceryDao().getItemById(tomato)!!.quantity)
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
    }
    private fun state(): ChecklistUiState {
        var state: ChecklistUiState? = null
        scenario!!.onActivity { state = ViewModelProvider(it)[RecipeViewModel::class.java].uiState.value }
        return state!!
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(150) }
        assertTrue("Checklist selection condition timed out", condition())
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun click(text: String) = visit(text, true)
    private fun visit(text: String, click: Boolean = false) {
        var attempts = 0
        eventually {
            val found = find(text)
            if (found != null) {
                if (!click) true else {
                    var node: AccessibilityNodeInfo = found
                    while (!node.isClickable && node.parent != null) node = node.parent
                    node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } else {
                val direction = if (attempts++ % 12 < 6) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    if (node.isScrollable && node.performAction(direction)) return true
                    for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                    return false
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::scroll); false
            }
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(instrumentation.targetContext.cacheDir, "checklist-selection-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

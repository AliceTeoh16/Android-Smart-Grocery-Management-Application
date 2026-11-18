package com.xmum.smartgrocery.ui.recipes

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
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

/** Real checklist bindings, with Room in RAM and the user's saved-checklist preference restored in finally. */
class ChecklistStepsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val database get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun stepsExpandScrollAndSurviveRotationWithoutUpdatingInventoryOrShopping(): Unit = runBlocking {
        val prefs = instrumentation.targetContext.getSharedPreferences("ai_checklist_store", Context.MODE_PRIVATE)
        val original = prefs.getString("saved_checklists", null)
        val originalPointer = prefs.getString("active_history_id", null)
        val originalMarker = prefs.getBoolean("room_history_snapshot_migrated", false)
        val gson = Gson()
        val missing = listOf(RecipeRepository.MissingChecklistItem("Cream Cheese", "400g", "Dairy"),
            RecipeRepository.MissingChecklistItem("Granulated Sugar", "100g", "Pantry"))
        val covered = listOf(RecipeRepository.DeductedInventoryItem("Eggs", "2 pcs (Fully Covered)"))
        val steps = listOf("Beat the cream cheese until smooth.", "Gradually add granulated sugar and mix well.",
            "Beat in the eggs one at a time.", "Bake at 160°C for 45 minutes, then cool before serving.")
        val checklist = SavedChecklist("steps-ui-test", "Cheesecake", 2, "pending", System.currentTimeMillis(), missing, covered,
            ingredients = listOf(RecipeRepository.ChecklistIngredient("Cream Cheese", "400g", false),
                RecipeRepository.ChecklistIngredient("Granulated Sugar", "100g", false), RecipeRepository.ChecklistIngredient("Eggs", "2 pcs", true)), steps = steps)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            database.clearAllTables()
            val eggs = database.groceryDao().insertWithLog(GroceryItem(name = "Eggs", quantity = 5, price = 2.0,
                category = "Dairy", expiryDate = 1900000000000L, threshold = 0)).toInt()
            database.checklistHistoryDao().insert(ChecklistHistoryItem(checklist.id, checklist.dishName, checklist.servings,
                checklist.status, checklist.createdAt, gson.toJson(missing), gson.toJson(covered)))
            assertTrue(prefs.edit().putString("saved_checklists", gson.toJson(listOf(checklist))).commit())
            val activity = ActivityScenario.launch(MainActivity::class.java); scenario = activity
            activity.onActivity { it.openPantryAction(PantryAction.AiChecklist) }
            click("View Recipe Steps"); visit(steps[0]); visit(steps.last())
            capture("expanded")
            activity.recreate()
            click("Hide Recipe Steps")
            visit("View Recipe Steps") // Wait for Compose to publish the collapsed state.
            assertNull(find(steps[0]))
            visit("Ingredient checklist"); visit("Add Selected (3) to Shopping List"); visit("Mark as cooked")
            assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            assertEquals("pending", database.checklistHistoryDao().getChecklistHistoryById(checklist.id)!!.status)
            assertEquals(listOf("ADD"), database.groceryDao().getLogsForItem(eggs).first().map { it.changeType })
            capture("collapsed-actions")
        } finally {
            scenario?.close()
            val edit = prefs.edit()
            if (original == null) edit.remove("saved_checklists") else edit.putString("saved_checklists", original)
            if (originalPointer == null) edit.remove("active_history_id") else edit.putString("active_history_id", originalPointer)
            edit.putBoolean("room_history_snapshot_migrated", originalMarker)
            assertTrue(edit.commit())
        }
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun visit(text: String, click: Boolean = false) {
        var attempts = 0
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            val found = find(text)
            if (found != null) {
                if (!click) return
                var node: AccessibilityNodeInfo = found
                while (!node.isClickable && node.parent != null) node = node.parent
                if (node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync(); return
                }
            } else {
                val direction = if (attempts++ % 12 < 6) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    if (node.isScrollable && node.performAction(direction)) return true
                    for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                    return false
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::scroll)
            }
            Thread.sleep(150)
        }
        fail("Could not ${if (click) "click" else "find"} $text")
    }
    private fun click(text: String) = visit(text, true)
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(instrumentation.targetContext.cacheDir, "checklist-steps-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

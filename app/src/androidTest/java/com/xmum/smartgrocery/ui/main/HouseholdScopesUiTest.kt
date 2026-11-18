package com.xmum.smartgrocery.ui.main

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Compose screens and Room observations; the app's production database is never used. */
class HouseholdScopesUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val db get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun dynamicPeopleSettingsAndPersonalHouseholdTabsSurviveRecreation(): Unit = runBlocking {
        val prefs = instrumentation.targetContext.getSharedPreferences("smart_inventory_auth", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            db.clearAllTables()
            db.useTestAccount("ui-owner")
            val manager = SessionManager(db)
            val month = DateUtils.getCurrentMonthYear()
            db.shoppingListDao().insertManual(ShoppingListItem(itemName = "Private toothpaste", category = "Personal Care",
                quantityNeeded = 1, estimatedUnitPrice = 4.0, assignedTo = "Me"))
            db.budgetDao().setBudget(BudgetEntry(month, 100.0))
            val home = manager.createHousehold("Garden family", "Groceries for our garden home")
            db.shoppingListDao().insertManual(ShoppingListItem(itemName = "Shared rice", category = "Pantry",
                quantityNeeded = 1, estimatedUnitPrice = 8.0))
            db.budgetDao().setBudget(BudgetEntry(month, 600.0))
            assertTrue(prefs.edit().clear().putBoolean("is_signed_in", true).putString("user_id", "ui-owner")
                .putString("email", "ui-owner@example.test").putString("display_name", "Test user").commit())
            val activity = ActivityScenario.launch(MainActivity::class.java); scenario = activity
            activity.onActivity { it.openPantryAction(PantryAction.Household) }
            visit("Share & Connect")
            visit("Garden family")
            visit("Groceries for our garden home")
            visit("Owner")
            visit("You · Active now")
            capture("household-header")
            visit(home.joinCode)
            visit("Share invitation link")
            visit("Expiry warning")
            click("+")
            eventually { runBlocking { db.householdDao().observeSettings().first()?.expiryWarningDays == 4 } }
            visit("4d")
            activity.recreate()
            visit("4d")
            capture("household-setting")
            activity.onActivity { it.openPantryAction(PantryAction.Shopping) }
            visit("Shopping List")
            click("Personal")
            visit("Private toothpaste")
            assertNull(find("All"))
            assertNull(find("Shared rice"))
            click("Household")
            visit("Shared rice")
            assertNull(find("Private toothpaste"))
            capture("shopping-scopes")
            activity.onActivity { it.openPantryAction(PantryAction.Budget) }
            visit("Budget Tracker")
            click("Personal")
            visit("RM 100.00")
            setLimit("125")
            eventually { runBlocking { db.budgetDao().getBudgetForMonthSync(month)?.limitAmount == 125.0 } }
            click("Household")
            visit("RM 600.00")
            setLimit("675")
            eventually { runBlocking { db.budgetDao().getBudgetForMonthSync(month)?.limitAmount == 675.0 } }
            click("Personal")
            visit("RM 125.00")
            click("Household")
            visit("RM 675.00")
            visit("Budget Tracker")
            capture("budget-scopes")
            assertEquals(home.householdId, manager.currentSession()!!.householdId)
        } finally {
            scenario?.close()
            val edit = prefs.edit().clear()
            for ((key, value) in saved) when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
            }
            edit.commit()
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            // Compose can update while UiAutomation still holds cached child text from the preceding screen.
            if (!node.refresh()) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> search(child)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }

    private fun visit(text: String, click: Boolean = false) {
        var attempts = 0
        val deadline = System.currentTimeMillis() + 15_000
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
    private fun setLimit(amount: String) {
        visit("Set Limit")
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> editable(child)?.let { return it } }
            return null
        }
        val field = instrumentation.uiAutomation.rootInActiveWindow?.let(::editable)
        assertNotNull("Budget input is visible", field)
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, amount)
        }))
        click("Set Limit")
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(condition())
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}

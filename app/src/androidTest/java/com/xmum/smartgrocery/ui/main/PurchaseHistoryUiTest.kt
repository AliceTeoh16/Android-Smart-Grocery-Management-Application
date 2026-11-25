package com.xmum.smartgrocery.ui.main

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/** Uses isolated Room data and the real Home shortcut; no sharing server or AI calls. */
class PurchaseHistoryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun homeOpensOfflineHistoryWithPrivateSharedSearchAndSavedMonthlyFilters(): Unit = runBlocking {
        val prefs = context.getSharedPreferences("smart_inventory_auth", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        var activity: ActivityScenario<MainActivity>? = null
        try {
            db.clearAllTables()
            db.useTestAccount("history-owner")
            val manager = SessionManager(db)
            val dao = db.groceryDao()
            val milk = dao.insertWithLog(GroceryItem(name = "Past milk", quantity = 2, price = 4.0,
                category = "Dairy", threshold = 0)).toInt()
            dao.delete(dao.getItemById(milk)!!)
            val lastMonth = Calendar.getInstance().apply { add(Calendar.MONTH, -1); set(Calendar.DAY_OF_MONTH, 5) }.timeInMillis
            dao.insertWithLog(GroceryItem(name = "Last month coffee", quantity = 1, price = 7.0,
                category = "Pantry", purchasedDate = lastMonth, threshold = 0))
            val home = manager.createHousehold("History household")
            dao.insertWithLog(GroceryItem(name = "Shared rice", quantity = 3, price = 5.0, category = "Pantry", threshold = 0))
            db.useTestAccount("history-friend")
            dao.insertWithLog(GroceryItem(name = "Friend private soap", quantity = 1, price = 2.0, category = "Personal Care", threshold = 0))
            manager.joinHousehold(home.joinCode)
            dao.insertWithLog(GroceryItem(name = "Shared eggs", quantity = 1, price = 3.0, category = "Dairy", threshold = 0))
            db.accountDao().setSession(LocalSessionEntity(userId = "history-owner", activeHouseholdId = home.householdId))
            prefs.edit().clear().putBoolean("is_signed_in", true).putString("user_id", "history-owner")
                .putString("email", "history-owner@example.test").putString("display_name", "History owner").commit()
            val scenario = ActivityScenario.launch(MainActivity::class.java); activity = scenario
            click("Purchase history")
            visit("RM 15.00")
            visit("Past milk") // Remains after the inventory item was deleted.
            assertNull(find("Friend private soap"))
            assertNull(find("Shared rice"))
            click("This month")
            visit("RM 8.00")
            search("coffee")
            visit("No matching purchases")
            click("All time")
            visit("Last month coffee")
            visit("RM 7.00")
            click("Clear search", description = true)
            visit("RM 15.00")
            capture("purchase-history-personal")
            click("Household")
            visit("History household · Change context")
            visit("RM 18.00")
            visit("Shared rice")
            assertNull(find("Past milk"))
            assertNull(find("Friend private soap"))
            search("rice")
            click("This month")
            visit("1 purchase record matching your search")
            scenario.recreate()
            visit("Shared rice")
            visit("1 purchase record matching your search")
            assertEquals("rice", editable(instrumentation.uiAutomation.rootInActiveWindow!!)?.text?.toString())
            click("Clear search", description = true)
            visit("RM 18.00")
            capture("purchase-history-household")
            click("Personal")
            visit("Past milk")
            visit("RM 8.00")

            // The three additional entry points use the same history and keep the selected scope.
            click("Back", description = true)
            click("Budget")
            visit("Budget Tracker")
            click("Purchase history")
            visit("Past milk")
            visit("RM 15.00")
            click("Household")
            visit("RM 18.00")
            click("Back", description = true)
            visit("Budget Tracker")
            click("Shopping")
            visit("Shopping List")
            click("Purchase history")
            visit("Shared rice")
            visit("RM 18.00")
            assertNull(find("Past milk"))
            click("Back", description = true)
            visit("Shopping List")
            // Back now retains Shopping. Open Home explicitly to exercise its nested profile entry.
            click("Home", description = true)
            click("Open profile", description = true)
            visit("Profile & Settings")
            click("Purchase history")
            visit("Shared rice")
            visit("RM 18.00")
            assertNull(find("Profile & Settings"))
            click("Back", description = true)
            visit("Profile & Settings")
        } finally {
            activity?.close()
            val restore = prefs.edit().clear()
            for ((key, value) in before) when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
            }
            restore.commit()
        }
    }

    private fun find(text: String, description: Boolean = false): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if ((if (description) node.contentDescription else node.text)?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun visit(text: String, click: Boolean = false, description: Boolean = false) {
        var attempts = 0
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val found = find(text, description)
            if (found != null) {
                if (!click) return
                var target: AccessibilityNodeInfo = found
                while (!target.isClickable && target.parent != null) target = target.parent
                if (target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
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
    private fun click(text: String, description: Boolean = false) = visit(text, true, description)
    private fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { field -> return field } }
        return null
    }
    private fun search(value: String) {
        visit("Search purchases")
        val field = editable(instrumentation.uiAutomation.rootInActiveWindow!!)
        assertNotNull(field)
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.cacheDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}

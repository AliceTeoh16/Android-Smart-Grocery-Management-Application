package com.xmum.smartgrocery.ui.main

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.data.sharing.*
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CrossDeviceSharingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val db get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun connectAndPublishThroughTheUiThenAutomaticallyPullAnotherDevicesBudget(): Unit = runBlocking {
        val authPrefs = context.getSharedPreferences("smart_inventory_auth", Context.MODE_PRIVATE)
        val authBefore = authPrefs.all.toMap()
        val config = context.getSharedPreferences("household_sharing", Context.MODE_PRIVATE)
        val previousServer = config.getString("server_url", null)
        val peer = androidx.room.Room.inMemoryDatabaseBuilder(context, com.xmum.smartgrocery.data.local.AppDatabase::class.java).build()
        var activity: ActivityScenario<MainActivity>? = null
        try {
            db.clearAllTables()
            val email = "ui-owner-${UUID.randomUUID()}@example.test"
            val local = SessionManager(db)
            assertNull(local.register("Cloud owner", email, "correct123"))
            val userId = local.currentUserId()!!
            db.groceryDao().insertWithLog(GroceryItem(name = "Personal secret", quantity = 1, price = 2.0, category = "Pantry", threshold = 0))
            val home = local.createHousehold("Connected family", "Shared across devices")
            db.groceryDao().insertWithLog(GroceryItem(name = "Shared household stock", quantity = 2, price = 5.0, category = "Pantry", threshold = 0))
            val month = DateUtils.getCurrentMonthYear()
            db.budgetDao().setBudget(BudgetEntry(month, 200.0))
            authPrefs.edit().clear().putBoolean("is_signed_in", true).putString("user_id", userId)
                .putString("email", email).putString("display_name", "Cloud owner").commit()
            SharingRuntime.disabledForTests = false
            val runtime = SharingRuntime.install(context, db)!!
            runtime.configureServer("http://10.0.2.2:8791/")
            val scenario = ActivityScenario.launch(MainActivity::class.java); activity = scenario
            scenario.onActivity { it.openPantryAction(PantryAction.Household) }
            click("Connect account")
            enterPassword("correct123")
            click("Connect")
            visit("Enable cross-device sharing for this household")
            click("Enable cross-device sharing for this household")
            eventually { runBlocking { db.sharingDao().household(home.householdId) != null && db.sharingDao().pending().first().isEmpty() } }
            val code = db.sharingDao().localHousehold(home.householdId)!!.joinCode
            val peerService = SharingService(peer, { "http://10.0.2.2:8791/" })
            assertNull(peerService.register("Cloud member", "ui-peer-${UUID.randomUUID()}@example.test", "correct123"))
            peerService.joinHousehold(code)
            assertTrue(peer.groceryDao().getAllItemsOnce().none { it.name == "Personal secret" })
            peer.budgetDao().setBudget(BudgetEntry(month, 250.0))
            peerService.synchronize()
            visit("Cloud member") // The real app's periodic sync updates the member list.
            visit("Member")
            capture("cross-device-share")
            scenario.onActivity { it.openPantryAction(PantryAction.Budget) }
            click("Household")
            visit("RM 250.00")
            capture("cross-device-budget")
            local.selectScope(false)
            assertEquals("Personal secret", db.groceryDao().getAllItemsOnce().single().name)
        } finally {
            activity?.close()
            SharingRuntime.uninstallForTests(db)
            SharingRuntime.disabledForTests = true
            peer.close()
            val restore = authPrefs.edit().clear()
            for ((key, value) in authBefore) when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
            }
            restore.commit()
            if (previousServer == null) config.edit().remove("server_url").commit() else config.edit().putString("server_url", previousServer).commit()
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> search(child)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun visit(text: String, click: Boolean = false) {
        var attempts = 0
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            val found = find(text)
            if (found != null) {
                if (!click) return
                var node: AccessibilityNodeInfo = found
                while (!node.isClickable && node.parent != null) node = node.parent
                if (node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { instrumentation.waitForIdleSync(); return }
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
    private fun enterPassword(value: String) {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.isPassword) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { found -> return found } }
            return null
        }
        val input = instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
        assertNotNull(input)
        assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 40_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(150)
        assertTrue(condition())
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

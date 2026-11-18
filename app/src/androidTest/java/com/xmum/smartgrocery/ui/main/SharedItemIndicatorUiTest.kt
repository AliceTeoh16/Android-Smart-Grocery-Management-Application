package com.xmum.smartgrocery.ui.main

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SharedItemIndicatorUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = (instrumentation as VoiceCommandTestRunner).database
    private suspend fun actor(id: String, name: String) {
        db.useTestAccount(id)
        db.sharingDao().updateUser(db.accountDao().currentUser()!!.copy(displayName = name))
    }

    @Test fun inventoryCardShowsLatestMemberChangeEvenWhenThatBatchIsEmpty(): Unit = runBlocking {
        db.clearAllTables(); actor("alice", "Alice")
        val manager = SessionManager(db)
        val home = manager.createHousehold("Family pantry")
        val expiry = System.currentTimeMillis() + 30L * 86400000
        db.groceryDao().insert(GroceryItem(name = "Milk", category = "Dairy", quantity = 2, price = 3.0, expiryDate = expiry))
        actor("ben", "Ben"); manager.joinHousehold(home.joinCode)
        val second = db.groceryDao().insert(GroceryItem(name = "Milk", category = "Dairy", quantity = 1, price = 3.0, expiryDate = expiry + 86400000)).toInt()
        db.groceryDao().updateQuantity(second, 0, System.currentTimeMillis() + 10000)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            scrollTo("Updated by Ben")
            assertNotNull(findText("Updated by Ben"))
            screenshot("shared-inventory-author.png")
        } finally { scenario.close() }
    }

    @Test fun shoppingCardShowsCreatorThenCheckerAndHidesIndicatorsForPersonalItems(): Unit = runBlocking {
        db.clearAllTables(); actor("alice", "Alice")
        val manager = SessionManager(db)
        db.shoppingListDao().insertManual(ShoppingListItem(itemName = "Private toothpaste", category = "Personal Care",
            quantityNeeded = 1, estimatedUnitPrice = 2.0, addedBy = "Manual", assignedTo = "Me"))
        val home = manager.createHousehold("Family pantry")
        actor("ben", "Ben"); manager.joinHousehold(home.joinCode)
        val id = db.shoppingListDao().insertManual(ShoppingListItem(itemName = "Eggs", category = "Dairy",
            quantityNeeded = 2, estimatedUnitPrice = 3.0, addedBy = "Voice", assignedTo = "Household")).toInt()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Shopping) }
            scrollTo("Added by Ben")
            screenshot("shared-shopping-added.png")
            db.accountDao().setSession(LocalSessionEntity(userId = "alice", householdId = home.householdId))
            db.shoppingListDao().setChecked(id, true)
            eventually { findText("Checked by Alice") != null }
            screenshot("shared-shopping-checked.png")
            manager.selectHousehold(null)
            scrollTo("Private toothpaste")
            assertNull(findText("Checked by Alice")); assertNull(findText("Added by Alice")); assertNull(findText("Added by Manual"))
            screenshot("personal-shopping-no-author.png")
        } finally { scenario.close() }
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun scrollTo(text: String) {
        fun scroll(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
            return false
        }
        repeat(20) {
            if (findText(text) != null) return
            instrumentation.uiAutomation.rootInActiveWindow?.let(::scroll)
            Thread.sleep(250)
        }
        eventually { findText(text) != null }
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Condition timed out", condition())
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(500)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

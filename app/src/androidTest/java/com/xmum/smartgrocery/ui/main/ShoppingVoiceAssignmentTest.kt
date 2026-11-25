package com.xmum.smartgrocery.ui.main

import com.xmum.smartgrocery.data.local.useTestAccount
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.VoiceParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All additions are isolated in the runner's RAM database, never the user's grocery data. */
@RunWith(AndroidJUnit4::class)
class ShoppingVoiceAssignmentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val database get() = (instrumentation as VoiceCommandTestRunner).database
    private val dao get() = database.shoppingListDao()
    private suspend fun rows() = dao.getShoppingListItems().first()
    private fun egg(owner: String, quantity: Int = 1) = ShoppingListItem(itemName = "Eggs", category = "Dairy",
        quantityNeeded = quantity, estimatedUnitPrice = 3.5, assignedTo = owner, addedBy = "Manual")

    @Test fun reviewAssignmentSurvivesEditAndRecreationAndOnlyConfirmationMerges(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val manager = SessionManager(database)
        dao.insertManual(egg("Me"))
        val home = manager.createHousehold("Test pantry")
        dao.insertManual(egg("Household", 4))
        manager.selectHousehold(null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Shopping); it.editVoiceCommand("buy 2 eggs", true) }
            click("Review command"); waitFor("Who is this for?")
            click("Me only"); waitFor("Existing 1 + 2 = 3")
            assertEquals(1, rows().single().quantityNeeded)
            scenario.recreate(); waitFor("Existing 1 + 2 = 3")
            scenario.onActivity {
                assertEquals("Me", (it.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG)
                    as VoiceCommandConfirmationDialog).shoppingAssignee)
            }
            click("Edit command"); click("Review command"); waitFor("Existing 1 + 2 = 3")
            click("Cancel")
            assertEquals(1, rows().single { it.assignedTo == "Me" }.quantityNeeded)
            scenario.onActivity { it.editVoiceCommand("买两个鸡蛋", true, "Me") }
            click("Review command"); waitFor("Existing 1 + 2 = 3"); click("Confirm")
            eventually { runBlocking { rows().single { it.assignedTo == "Me" }.quantityNeeded == 3 } }
            assertEquals(3.5, rows().single { it.assignedTo == "Me" }.estimatedUnitPrice, 0.0)
            manager.selectHousehold(home.householdId)
            assertEquals(4, rows().single { it.assignedTo == "Household" }.quantityNeeded)
        } finally { scenario.close() }
    }

    @Test fun householdChoiceAppliesToEveryItemAndKeepsPrivateRowsSeparate(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val manager = SessionManager(database)
        dao.insertManual(egg("Me"))
        manager.createHousehold("Test pantry")
        dao.insertManual(egg("Household"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Shopping)
                it.editVoiceCommand("Beli dua biji telur dan satu botol susu", true, "Me") }
            click("Review command"); click("Household"); waitFor("Existing 1 + 2 = 3")
            click("Confirm")
            eventually { runBlocking { rows().size == 2 && rows().single { it.assignedTo == "Household" && it.itemName == "Eggs" }.quantityNeeded == 3 } }
            assertEquals("Household", rows().single { it.itemName == "milk" }.assignedTo)
            manager.selectHousehold(null)
            assertEquals(1, rows().single { it.assignedTo == "Me" }.quantityNeeded)
        } finally { scenario.close() }
    }

    @Test fun repeatedAndConcurrentCommandsMergeAtomicallyAndOverflowRollsBack(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val eggId = dao.insertManual(egg("Me"))
        (1..4).map { async { dao.addVoiceItems(VoiceParser.parseAll("buy 2 eggs"), "Me") } }.awaitAll()
        assertEquals(1, rows().size)
        assertEquals(9, rows().single().quantityNeeded)
        assertEquals(eggId, rows().single().id.toLong())
        dao.addVoiceItems(VoiceParser.parseAll("buy 1 egg and 2 eggs"), "Me")
        assertEquals(12, rows().single().quantityNeeded)
        dao.update(rows().single().copy(quantityNeeded = Int.MAX_VALUE))
        var failed = false
        try { dao.addVoiceItems(VoiceParser.parseAll("buy 1 milk and 2 eggs"), "Me") }
        catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
        assertEquals(1, rows().size)
        assertEquals(Int.MAX_VALUE, rows().single().quantityNeeded)
    }

    @Test fun restockRefreshRequiresApprovalAndPreservesConfirmedVoiceQuantity(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val inventoryId = database.groceryDao().insert(GroceryItem(name = "Eggs", category = "Dairy", quantity = 1,
            price = 3.5, threshold = 2, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val stock = database.groceryDao().getItemById(inventoryId)!!
        dao.syncLowStockItems(listOf(stock))
        assertTrue(rows().isEmpty())
        dao.approveRestocks(mapOf(inventoryId to 1))
        dao.addVoiceItems(VoiceParser.parseAll("buy 2 eggs"), "Me")
        dao.syncLowStockItems(listOf(stock))
        assertEquals(3, rows().single().quantityNeeded)
        assertEquals(inventoryId, rows().single().sourceItemId)
        database.groceryDao().updateQuantityWithLog(inventoryId, 5)
        dao.syncLowStockItems(database.groceryDao().getAllItemsOnce())
        assertEquals(3, rows().single().quantityNeeded)
        assertEquals(0, dao.approveRestocks(mapOf(inventoryId to 2)))
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { search(it) }
    }
    private fun waitFor(text: String) = eventually { find(text) != null }
    private fun click(text: String) {
        repeat(12) {
            if (find(text) != null) return@repeat
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                return false
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let { scroll(it) }
            Thread.sleep(150)
        }
        waitFor(text)
        var node = find(text)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun eventually(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Shopping voice condition timed out", condition())
    }
}

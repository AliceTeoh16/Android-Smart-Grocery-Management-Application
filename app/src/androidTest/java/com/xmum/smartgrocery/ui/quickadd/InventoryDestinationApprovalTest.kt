package com.xmum.smartgrocery.ui.quickadd

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.databinding.BottomSheetQuickAddBinding
import com.xmum.smartgrocery.ui.common.RestockMonitor
import com.xmum.smartgrocery.ui.inventory.RestockReviewActivity
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real UI and commit paths; recognizer responses are fixtures and the database is RAM only. */
class InventoryDestinationApprovalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun manualEntryDefaultsPrivateAndExplicitHouseholdSurvivesRecreation(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("alice")
        val manager = SessionManager(db)
        val home = manager.createHousehold("Chosen household")
        val other = manager.createHousehold("Other household")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            showForm(scenario, "Private milk")
            waitFor("Personal (private)")
            assertEquals(other.householdId, manager.currentSession()!!.householdId)
            saveForm(scenario)
            eventually { runBlocking { db.groceryDao().getAllItemsOnce().any { it.name == "Private milk" } } }
            assertNull(db.groceryDao().getAllItemsOnce().single().householdId)
            assertNull(manager.currentSession()!!.householdId)
            manager.selectHousehold(other.householdId)
            showForm(scenario, "Shared eggs")
            waitFor("Personal (private)"); click("Household"); waitFor("Other household")
            click("Choose household"); click("Chosen household"); waitFor("Chosen household")
            scenario.recreate(); waitFor("Chosen household")
            screenshot("inventory-destination.png")
            saveForm(scenario)
            eventually { runBlocking { db.groceryDao().getAllItemsOnce().any { it.name == "Shared eggs" } } }
            assertEquals(home.householdId, db.groceryDao().getAllItemsOnce().single().householdId)
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
            db.useTestAccount("bob"); manager.joinHousehold(home.joinCode)
            assertEquals(listOf("Shared eggs"), db.groceryDao().getAllItemsOnce().map { it.name })
            manager.selectHousehold(null); assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
        } finally { scenario.close() }
    }

    @Test fun photoReviewDefaultsPrivateAndSharedBatchUsesExplicitDestination(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("photo-user")
        val manager = SessionManager(db)
        val home = manager.createHousehold("Photo household")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            fun openPhoto(name: String) {
                scenario.onActivity { activity ->
                    val fragment = IngredientScanBottomSheetDialog()
                    fragment.showNow(activity.supportFragmentManager, IngredientScanBottomSheetDialog.TAG)
                    val model = ViewModelProvider(fragment)[IngredientScanViewModel::class.java]
                    model.recognizeFood = { listOf(VisualFoodItem(name, "Food & Beverage", 1, "Produce")) }
                    model.scan(Uri.parse("content://fixture/photo"))
                }
                waitFor("Batch Review Checklist"); waitFor("Personal (private)")
            }
            openPhoto("Private tomato")
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            click("Add All to Inventory")
            eventually { runBlocking { manager.currentSession()!!.householdId == null && db.groceryDao().getAllItemsOnce().size == 1 } }
            assertEquals("Private tomato", db.groceryDao().getAllItemsOnce().single().name)
            manager.selectHousehold(home.householdId)
            openPhoto("Shared pepper")
            click("Household"); waitFor("Photo household")
            screenshot("photo-destination.png")
            click("Add All to Inventory")
            eventually { runBlocking { db.groceryDao().getAllItemsOnce().any { it.name == "Shared pepper" } } }
            assertEquals(home.householdId, db.groceryDao().getAllItemsOnce().single().householdId)
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
        } finally { scenario.close() }
    }

    @Test fun voiceStartsWithDestinationChoiceAndCommitKeepsReviewedScope(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("voice-user")
        val manager = SessionManager(db)
        val home = manager.createHousehold("Voice household")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory); it.triggerVoiceInput() }
            waitFor("Voice inventory destination"); waitFor("Personal (private)")
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            click("Household"); waitFor("Voice household"); screenshot("voice-destination.png")
            click("Cancel")
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            manager.selectHousehold(null)
            scenario.onActivity { it.viewModel.processConfirmedInventoryVoiceCommand("add 1 milk",
                mapOf(0 to GroceryItem(name = "Milk", category = "Dairy", quantity = 1, price = 0.0)),
                DataScope("voice-user", home.householdId)) }
            eventually { runBlocking { db.groceryDao().getAllItemsOnce().size == 1 } }
            assertEquals(home.householdId, db.groceryDao().getAllItemsOnce().single().householdId)
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
        } finally { scenario.close() }
    }

    @Test fun notificationAndOpeningReviewDoNotAddUntilExplicitApproval(): Unit = runBlocking {
        db.clearAllTables()
        val actor = "notify-${UUID.randomUUID()}"
        db.useTestAccount(actor)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val id = db.groceryDao().insert(GroceryItem(name = "Eggs", category = "Dairy", quantity = 0, threshold = 2, price = 1.0)).toInt()
        val monitor = RestockMonitor(context, db)
        val job = monitor.start()
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            eventually { notifications.activeNotifications.any { it.tag?.startsWith("restock:$actor:") == true } }
            val notification = notifications.activeNotifications.first { it.tag?.startsWith("restock:$actor:") == true }.notification
            assertNotNull(notification.contentIntent)
            assertEquals("Review restock", notification.actions.single().title.toString())
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
            var scenario = ActivityScenario.launch<RestockReviewActivity>(RestockReviewActivity.intent(context, DataScope(actor, null)))
            try { waitFor("Review restock"); waitFor("Eggs"); click("Cancel") } finally { scenario.close() }
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
            scenario = ActivityScenario.launch(RestockReviewActivity.intent(context, DataScope(actor, null)))
            try {
                waitFor("Eggs")
                eventually { find { it.isCheckable && it.isClickable } != null }
                assertTrue(find { it.isCheckable && it.isClickable }!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                eventually { find { it.isCheckable && it.isChecked } != null }
                Thread.sleep(200)
                screenshot("restock-approval.png")
                click("Add selected to shopping list")
                eventually { runBlocking { db.shoppingListDao().getShoppingListItems().first().size == 1 } }
            } finally { scenario.close() }
            assertEquals(id, db.shoppingListDao().getShoppingListItems().first().single().sourceItemId)
            assertEquals(3, db.shoppingListDao().getShoppingListItems().first().single().quantityNeeded)
            assertEquals(0, db.shoppingListDao().approveRestocks(mapOf(id to 3)))
        } finally { monitor.stop(); job.join() }
    }

    private fun showForm(scenario: ActivityScenario<MainActivity>, name: String) {
        eventually {
            var gone = false
            scenario.onActivity { it.supportFragmentManager.executePendingTransactions(); gone = it.supportFragmentManager.findFragmentByTag("form") == null }
            gone
        }
        scenario.onActivity { QuickAddBottomSheetDialog.newInstance(name).showNow(it.supportFragmentManager, "form") }
    }
    private fun saveForm(scenario: ActivityScenario<MainActivity>) = scenario.onActivity {
        val form = it.supportFragmentManager.findFragmentByTag("form") as QuickAddBottomSheetDialog
        val binding = QuickAddBottomSheetDialog::class.java.getDeclaredField("_binding").apply { isAccessible = true }.get(form) as BottomSheetQuickAddBinding
        binding.buttonSave.performClick()
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { match -> return match } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun waitFor(text: String) = eventually { find { it.text?.toString() == text } != null }
    private fun click(text: String) {
        waitFor(text)
        var node = find { it.text?.toString() == text }!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Condition timed out", condition())
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

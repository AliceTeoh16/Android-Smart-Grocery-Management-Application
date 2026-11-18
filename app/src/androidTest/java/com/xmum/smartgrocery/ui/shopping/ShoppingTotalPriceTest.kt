package com.xmum.smartgrocery.ui.shopping

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.databinding.BottomSheetShoppingItemBinding
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Verify entered totals through the real form, cart, purchase, and spending history. */
class ShoppingTotalPriceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun personalTotalSurvivesRecreationAndCountsOnceInCartAndPurchase(): Unit = runBlocking {
        verifyTotal("Milk", "Dairy", "Me", 3, "12.00", 3, recreate = true)
    }

    @Test fun householdPantryTotalRemainsCorrectWithExistingPackageQuantityBehavior(): Unit = runBlocking {
        verifyTotal("Rice", "Pantry", "Household", 3, "10.00", 1, recreate = false)
    }

    private suspend fun verifyTotal(name: String, category: String, assignee: String, quantity: Int,
        total: String, purchasedQuantity: Int, recreate: Boolean) {
        db.clearAllTables(); db.useTestAccount("total-buyer")
        val manager = SessionManager(db)
        val home = manager.createHousehold("Price household")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: ShoppingListViewModel
            scenario.onActivity { activity ->
                activity.openPantryAction(PantryAction.Shopping)
                activity.supportFragmentManager.executePendingTransactions()
                model = ViewModelProvider(activity.supportFragmentManager.findFragmentById(R.id.fragment_container)!!)[ShoppingListViewModel::class.java]
                AddShoppingItemDialog.newInstance(name, quantity).showNow(activity.supportFragmentManager, "total-price-form")
            }
            scenario.onActivity { activity ->
                val binding = formBinding(activity)
                binding.editShoppingPrice.setText(total)
                binding.spinnerShoppingCategory.setText(category, false)
                binding.spinnerShoppingAssignee.setText(assignee, false)
            }
            eventually { findText("Total price (RM)") != null }
            assertNull(findText("Unit price (RM)"))
            if (recreate) {
                scenario.recreate()
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.executePendingTransactions()
                    model = ViewModelProvider(activity.supportFragmentManager.findFragmentById(R.id.fragment_container)!!)[ShoppingListViewModel::class.java]
                    assertEquals(total, formBinding(activity).editShoppingPrice.text.toString())
                    assertEquals(quantity.toString(), formBinding(activity).editShoppingQuantity.text.toString())
                }
            }
            instrumentation.waitForIdleSync(); Thread.sleep(500)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "shopping-total-price-$assignee.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            scenario.onActivity { formBinding(it).buttonSaveShopping.performClick() }
            eventually { runBlocking { db.shoppingListDao().getShoppingListItems().first().size == 1 } }
            val row = db.shoppingListDao().getShoppingListItems().first().single()
            val amount = total.toDouble()
            assertEquals(amount / quantity, row.estimatedUnitPrice, 0.000001)
            assertEquals(if (assignee == "Me") null else home.householdId, row.householdId)
            val impact = withTimeout(5000) { model.budgetImpact.first { it.cartTotal > 0 } }
            assertEquals(amount, impact.cartTotal, 0.000001)
            scenario.onActivity { model.setItemChecked(row, true) }
            eventually { runBlocking { db.shoppingListDao().getShoppingListItems().first().isEmpty() && db.groceryDao().getPurchaseHistory().first().isNotEmpty() } }
            val purchased = db.groceryDao().getAllItemsOnce().single()
            assertEquals(purchasedQuantity, purchased.quantity)
            assertEquals(amount, purchased.quantity * purchased.price, 0.000001)
            assertEquals(amount, db.groceryDao().getPurchaseHistory().first().sumOf { it.amountChanged * it.unitPriceAtChange }, 0.000001)
        } finally { scenario.close() }
    }

    private fun formBinding(activity: MainActivity): BottomSheetShoppingItemBinding {
        val dialog = activity.supportFragmentManager.findFragmentByTag("total-price-form") as AddShoppingItemDialog
        return AddShoppingItemDialog::class.java.getDeclaredField("_binding").apply { isAccessible = true }.get(dialog) as BottomSheetShoppingItemBinding
    }
    private fun findText(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Condition timed out", condition())
    }
}

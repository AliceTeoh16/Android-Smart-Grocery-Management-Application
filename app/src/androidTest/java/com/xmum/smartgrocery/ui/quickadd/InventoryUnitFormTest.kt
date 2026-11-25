package com.xmum.smartgrocery.ui.quickadd

import android.view.accessibility.AccessibilityNodeInfo
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.databinding.BottomSheetQuickAddBinding
import com.xmum.smartgrocery.ui.CategoryMenuTestSupport
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InventoryUnitFormTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val db get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun decrementKeepsOtherUnitsAndStorageLocationsSeparate(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("unit-isolation")
        val pieceId = db.groceryDao().insertWithLog(com.xmum.smartgrocery.data.local.entity.GroceryItem(
            name="Eggs", quantity=12, price=1.0, category="Dairy", unit="pcs", storageLocation="Fridge")).toInt()
        val weightId = db.groceryDao().insertWithLog(com.xmum.smartgrocery.data.local.entity.GroceryItem(
            name="Eggs", quantity=500, price=0.01, category="Dairy", unit="g", storageLocation="Fridge")).toInt()
        val pantryId = db.groceryDao().insertWithLog(com.xmum.smartgrocery.data.local.entity.GroceryItem(
            name="Eggs", quantity=6, price=1.0, category="Dairy", unit="pcs", storageLocation="Pantry")).toInt()
        repeat(3) { db.groceryDao().consumeCardQuantity("Eggs", "pcs", "Fridge", 1) }
        assertEquals(9, db.groceryDao().getItemById(pieceId)!!.quantity)
        assertEquals(500, db.groceryDao().getItemById(weightId)!!.quantity)
        assertEquals(6, db.groceryDao().getItemById(pantryId)!!.quantity)
    }

    @Test fun dozenAndStorageSurviveRotationAndSaveTwelveEggsWithOnePurchaseTotal(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("unit-buyer")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            click("Add grocery item")
            eventually { find("Choose Unit") != null }
            scenario.onActivity {
                binding(it).apply { editItemName.setText("Eggs"); editQuantity.setText("1"); editPrice.setText("10.00") }
            }
            click("Choose Unit"); click("dozen")
            click("Choose Storage"); click("Fridge")
            scenario.recreate()
            eventually { find("dozen") != null }
            eventually { find("Fridge") != null }
            scenario.onActivity { binding(it).buttonSave.performClick() }
            eventually { runBlocking { db.groceryDao().getAllItemsOnce().size == 1 } }
            val saved = db.groceryDao().getAllItemsOnce().single()
            assertEquals(12, saved.quantity); assertEquals("pcs", saved.unit); assertEquals("Fridge", saved.storageLocation)
            assertEquals(10.0, saved.quantity * saved.price, 0.000001)
            val purchase = db.groceryDao().getPurchaseHistory().first().single()
            assertEquals(12, purchase.amountChanged); assertEquals("pcs", purchase.unit)
            assertEquals(10.0, purchase.amountChanged * purchase.unitPriceAtChange, 0.000001)
            scenario.onActivity { it.showEditItemBottomSheet(saved) }
            eventually { find("Edit inventory item") != null }
            scenario.onActivity {
                assertEquals("10.00", binding(it).editPrice.text.toString())
                binding(it).buttonSave.performClick()
            }
            eventually { find("Edit inventory item") == null }
            assertEquals(saved.unit, db.groceryDao().getItemById(saved.id)!!.unit)
            assertEquals(saved.storageLocation, db.groceryDao().getItemById(saved.id)!!.storageLocation)
            db.groceryDao().updateQuantity(saved.id, 11)
            assertEquals(11, db.groceryDao().getItemById(saved.id)!!.quantity)
            assertEquals(1, db.groceryDao().getPurchaseHistory().first().size)
            assertEquals(10.0, db.budgetDao().getTotalSpentForMonth(0, Long.MAX_VALUE).first(), 0.000001)
        } finally { scenario.close() }
    }

    @Test fun decimalKilogramsSaveAsGramsAndInvalidCountsDoNotWrite(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("weight-buyer")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            click("Add grocery item")
            eventually { find("Choose Unit") != null }
            scenario.onActivity { binding(it).apply { editItemName.setText("Rice"); editQuantity.setText("oops"); editPrice.setText("5.00") } }
            click("Choose Unit"); click("kg")
            scenario.onActivity { binding(it).buttonSave.performClick() }
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            scenario.onActivity { binding(it).apply { editQuantity.setText("0.5"); buttonSave.performClick() } }
            eventually { runBlocking { db.groceryDao().getAllItemsOnce().size == 1 } }
            val saved = db.groceryDao().getAllItemsOnce().single()
            assertEquals(500, saved.quantity); assertEquals("g", saved.unit)
            assertEquals(5.0, saved.quantity * saved.price, 0.000001)
        } finally { scenario.close() }
    }

    private fun binding(activity: MainActivity): BottomSheetQuickAddBinding {
        fun all(fragments: List<Fragment>): List<Fragment> = fragments.flatMap { listOf(it) + all(it.childFragmentManager.fragments) }
        val fragment = all(activity.supportFragmentManager.fragments).filterIsInstance<QuickAddBottomSheetDialog>().first()
        return QuickAddBottomSheetDialog::class.java.getDeclaredField("_binding").apply { isAccessible = true }
            .get(fragment) as BottomSheetQuickAddBinding
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun click(label: String) {
        CategoryMenuTestSupport.reveal(instrumentation, label)
        eventually {
            var node = find(label)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Inventory unit form did not reach the expected state", condition())
    }
}

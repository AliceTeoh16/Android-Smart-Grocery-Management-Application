package com.xmum.smartgrocery.ui.quickadd

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.fragment.app.Fragment
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.databinding.BottomSheetQuickAddBinding
import com.xmum.smartgrocery.ui.CategoryMenuTestSupport
import com.xmum.smartgrocery.utils.GroceryCategories
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InventoryCategoryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun categoriesAppearAcrossInventoryShoppingAndBudgetAndNonFoodFormHasNoSubcategory(): Unit = runBlocking {
        val database = (instrumentation as VoiceCommandTestRunner).database
        database.clearAllTables()
        database.groceryDao().insertBatchWithLogs(listOf(
            GroceryItem(name = "Tomato", category = "Produce", quantity = 1, price = 2.4, threshold = 1),
            GroceryItem(name = "Shampoo", category = "Personal Care", quantity = 1, price = 8.0, threshold = 1),
            GroceryItem(name = "Toilet Paper", category = "Household Essentials", quantity = 1, price = 6.5, threshold = 1)))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            for ((action, name) in listOf(PantryAction.Inventory to "inventory", PantryAction.Shopping to "shopping", PantryAction.Budget to "budget")) {
                scenario.onActivity { it.openPantryAction(action) }
                for (category in listOf("Food & Beverage", "Household Essentials", "Personal Care")) {
                    waitFor(if (action == PantryAction.Shopping && category == "Food & Beverage") "$category · Produce" else category)
                }
                capture("scope-$name.png")
            }
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            click("Add grocery item")
            click("Choose Category")
            click("Household Essentials")
            waitFor("Not Set")
            assertNull(find("Choose Subcategory"))
            capture("scope-household-form.png")
            click("Choose Category")
            click("Food & Beverage")
            waitFor("Choose Subcategory")
            click("Choose Subcategory")
            click("Spices")
            waitFor("Spices")
        } finally { scenario.close() }
    }

    @Test fun addAndEditFormsOfferAllNineFoodOptionsAndStoreTheSelectedLabel(): Unit = runBlocking {
        val database = (instrumentation as VoiceCommandTestRunner).database
        database.clearAllTables()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            click("Add grocery item")
            for (label in GroceryCategories.foodSubcategories) {
                click("Choose Subcategory")
                click("Select Subcategory: $label")
                scenario.onActivity { assertEquals(label, form(it).spinnerCategory.text.toString()) }
            }
            click("Choose Subcategory")
            click("Select Subcategory: Beverages")
            scenario.onActivity { form(it).editItemName.setText("Tea") }
            capture("nine-food-add-form.png")
            scenario.onActivity { form(it).buttonSave.performClick() }
            waitUntil { runBlocking { database.groceryDao().getAllItemsOnce().size == 1 } }
            val original = database.groceryDao().getAllItemsOnce().single()
            assertEquals("Food & Beverage", original.category)
            assertEquals("Beverages", original.subcategory)
            scenario.onActivity { it.showEditItemBottomSheet(original) }
            waitFor("Edit inventory item")
            for (label in listOf("Frozen Foods", "Snacks & Sweets")) {
                click("Choose Subcategory")
                click("Select Subcategory: $label")
                scenario.onActivity { assertEquals(label, form(it).spinnerCategory.text.toString()) }
            }
            capture("nine-food-edit-form.png")
            scenario.onActivity { form(it).buttonSave.performClick() }
            waitUntil { runBlocking { database.groceryDao().getItemById(original.id)?.subcategory == "Snacks & Sweets" } }
            val edited = database.groceryDao().getItemById(original.id)!!
            assertEquals(original.name, edited.name)
            assertEquals(original.quantity, edited.quantity)
            assertEquals(original.purchasedDate, edited.purchasedDate)
        } finally { scenario.close() }
    }

    private fun form(activity: MainActivity): BottomSheetQuickAddBinding {
        fun all(fragments: List<Fragment>): List<Fragment> = fragments.flatMap { listOf(it) + all(it.childFragmentManager.fragments) }
        return all(activity.supportFragmentManager.fragments).filterIsInstance<QuickAddBottomSheetDialog>()
            .mapNotNull { QuickAddBottomSheetDialog::class.java.getDeclaredField("_binding").apply { isAccessible = true }.get(it) as? BottomSheetQuickAddBinding }
            .first()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Food category changes were not saved", condition())
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(text: String) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (find(text) != null) return
            Thread.sleep(100)
        }
        fail("Missing UI text: $text")
    }
    private fun click(text: String) {
        if (text.startsWith("Select Subcategory:")) CategoryMenuTestSupport.reveal(instrumentation, text)
        waitFor(text)
        var node = find(text)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}

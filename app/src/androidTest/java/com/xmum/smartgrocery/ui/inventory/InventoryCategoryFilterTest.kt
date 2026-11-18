package com.xmum.smartgrocery.ui.inventory

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.CategoryMenuTestSupport
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.Category
import com.xmum.smartgrocery.utils.DateUtils
import com.xmum.smartgrocery.utils.FoodSubcategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the runner's in-memory inventory; the user's inventory is never changed. */
@RunWith(AndroidJUnit4::class)
class InventoryCategoryFilterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: InventoryViewModel

    @Test fun categoryCombinesWithSearchAndExpiryAndAllCategoriesResetsOnlyCategory(): Unit = runBlocking {
        seedInventory()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openInventory(scenario)
            expectItems("Tomato", "Milk", "Dish Soap", "Toilet Paper", "Shampoo")
            capture("inventory-category-filters.png")
            category("Food & Beverage")
            expectItems("Tomato", "Milk")
            category("Household Essentials")
            expectItems("Dish Soap", "Toilet Paper")
            capture("inventory-household-filter.png")
            search("dish")
            expectItems("Dish Soap")
            category("Personal Care")
            expectItems()
            waitFor { find { it.text?.toString() == "No matching items" } != null }
            search("")
            expectItems("Shampoo")
            click("Expiring Soon")
            expectItems()
            category("Food & Beverage")
            expectItems("Milk")
            click("Expired")
            expectItems()
            category("Personal Care")
            expectItems("Old Lotion")
            category("All categories")
            expectItems("Old Lotion")
            assertEquals(InventoryFilter.EXPIRED, model.inventoryFilter.value)
            click("All / In Stock")
            expectItems("Tomato", "Milk", "Dish Soap", "Toilet Paper", "Shampoo")
        } finally { scenario.close() }
    }

    @Test fun categoryAndSearchSurviveActivityRecreation(): Unit = runBlocking {
        seedInventory()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openInventory(scenario)
            category("Household Essentials")
            search("paper")
            expectItems("Toilet Paper")
            scenario.recreate()
            readModel(scenario)
            expectItems("Toilet Paper")
            assertEquals(Category.HOUSEHOLD_ESSENTIALS, model.categoryFilter.value)
            assertEquals("paper", model.searchQuery.value)
            waitFor { find { it.contentDescription?.toString() == "Filter category: Household Essentials" } != null }
            search("")
            expectItems("Dish Soap", "Toilet Paper")
            assertEquals(7, (instrumentation as VoiceCommandTestRunner).database.groceryDao().getAllItemsOnce().size)
        } finally { scenario.close() }
    }

    @Test fun foodSubcategoriesFilterEveryFoodGroupAndResetWhenCategoryChanges(): Unit = runBlocking {
        seedInventory()
        (instrumentation as VoiceCommandTestRunner).database.groceryDao().insertBatchWithLogs(listOf(
            GroceryItem(name = "Fish", category = "Meat & Seafood", quantity = 1, price = 8.0),
            GroceryItem(name = "Bread", category = "Bakery", quantity = 1, price = 3.0),
            GroceryItem(name = "Pasta", category = "Pantry", quantity = 1, price = 4.0),
            GroceryItem(name = "Cumin", category = "Spices", quantity = 1, price = 2.0),
            GroceryItem(name = "Coffee", category = "Beverages", quantity = 1, price = 5.0),
            GroceryItem(name = "Chips", category = "Snacks & Sweets", quantity = 1, price = 3.0),
            GroceryItem(name = "Frozen dumplings", category = "Frozen Foods", quantity = 1, price = 7.0)))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openInventory(scenario)
            assertNull(find { it.contentDescription?.toString() == "Choose food subcategory" })
            category("Food & Beverage")
            expectItems("Tomato", "Milk", "Fish", "Bread", "Pasta", "Cumin", "Coffee", "Chips", "Frozen dumplings")
            click("Choose food subcategory")
            for (label in FoodSubcategory.entries.map { it.label } + "All food subcategories") {
                CategoryMenuTestSupport.reveal(instrumentation, "Filter food subcategory: $label")
                waitFor { find { it.contentDescription?.toString() == "Filter food subcategory: $label" } != null }
            }
            click("Filter food subcategory: Produce")
            expectItems("Tomato")
            capture("inventory-food-subcategory-filter.png")
            subcategory("Dairy")
            expectItems("Milk")
            search("tomato")
            expectItems()
            subcategory("Produce")
            expectItems("Tomato")
            search("")
            click("Expiring Soon")
            expectItems()
            subcategory("Dairy")
            expectItems("Milk")
            category("Household Essentials")
            expectItems()
            assertNull(model.subcategoryFilter.value)
            assertNull(find { it.contentDescription?.toString() == "Choose food subcategory" })
            click("All / In Stock")
            expectItems("Dish Soap", "Toilet Paper")
            category("Food & Beverage")
            expectItems("Tomato", "Milk", "Fish", "Bread", "Pasta", "Cumin", "Coffee", "Chips", "Frozen dumplings")
            subcategory("Spices")
            expectItems("Cumin")
            scenario.recreate()
            readModel(scenario)
            expectItems("Cumin")
            assertEquals(Category.FOOD_BEVERAGE, model.categoryFilter.value)
            assertEquals(FoodSubcategory.SPICES, model.subcategoryFilter.value)
            for ((label, name) in listOf("Meat & Seafood" to "Fish", "Bakery" to "Bread", "Pantry" to "Pasta",
                "Beverages" to "Coffee", "Snacks & Sweets" to "Chips", "Frozen Foods" to "Frozen dumplings")) {
                subcategory(label)
                expectItems(name)
            }
            subcategory("All food subcategories")
            expectItems("Tomato", "Milk", "Fish", "Bread", "Pasta", "Cumin", "Coffee", "Chips", "Frozen dumplings")
            category("Personal Care")
            expectItems("Shampoo")
            assertNull(model.subcategoryFilter.value)
            assertNull(find { it.contentDescription?.toString() == "Choose food subcategory" })
        } finally { scenario.close() }
    }

    private suspend fun seedInventory() {
        val database = (instrumentation as VoiceCommandTestRunner).database
        database.clearAllTables()
        database.groceryDao().insertBatchWithLogs(listOf(
            GroceryItem(name = "Tomato", category = "Produce", quantity = 1, price = 2.4,
                expiryDate = DateUtils.getEndOfToday() + 20 * 86400000L),
            GroceryItem(name = "Milk", category = "Dairy", quantity = 2, price = 5.0, expiryDate = DateUtils.getEndOfToday()),
            GroceryItem(name = "Dish Soap", category = "Household Essentials", quantity = 1, price = 4.0),
            GroceryItem(name = "Toilet Paper", category = "Household Essentials", quantity = 2, price = 6.5),
            GroceryItem(name = "Shampoo", category = "Personal Care", quantity = 3, price = 8.0),
            GroceryItem(name = "Old Lotion", category = "Personal Care", quantity = 1, price = 9.0,
                expiryDate = DateUtils.getStartOfToday() - 86400000L)))
        database.groceryDao().insert(GroceryItem(name = "Empty Cleaner", category = "Household Essentials", quantity = 0, price = 3.0))
    }

    private fun openInventory(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
        readModel(scenario)
        waitFor { find { it.contentDescription?.toString() == "Filter category: All categories" } != null }
    }

    private fun readModel(scenario: ActivityScenario<MainActivity>) {
        fun inventory(fragments: List<Fragment>): InventoryFragment? = fragments.firstNotNullOfOrNull {
            (it as? InventoryFragment) ?: inventory(it.childFragmentManager.fragments)
        }
        scenario.onActivity { activity ->
            model = ViewModelProvider(inventory(activity.supportFragmentManager.fragments)!!)[InventoryViewModel::class.java]
        }
    }

    private fun expectItems(vararg names: String) {
        waitFor { model.inventoryItems.value.map { it.item.name }.toSet() == names.toSet() }
        val count = names.size
        waitFor { find { it.text?.toString() == "$count ${if (count == 1) "item" else "items"}" } != null }
        if (names.isNotEmpty()) {
            val knownNames = setOf("Tomato", "Milk", "Dish Soap", "Toilet Paper", "Shampoo", "Old Lotion", "Empty Cleaner", "Fish", "Bread", "Pasta", "Cumin", "Coffee", "Chips", "Frozen dumplings")
            waitFor {
                val rendered = knownNames.filter { name -> find { it.text?.toString() == name } != null }
                rendered.isNotEmpty() && rendered.all { it in names }
            }
        }
    }

    private fun category(label: String) {
        click("Filter category: $label")
        waitFor { model.categoryFilter.value?.label == label || (label == "All categories" && model.categoryFilter.value == null) }
    }

    private fun subcategory(label: String) {
        click("Choose food subcategory")
        click("Filter food subcategory: $label")
        waitFor { model.subcategoryFilter.value?.label == label || (label == "All food subcategories" && model.subcategoryFilter.value == null) }
    }

    private fun search(value: String) {
        waitFor { find { it.isEditable } != null }
        assertTrue(find { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        instrumentation.waitForIdleSync()
    }

    private fun click(label: String) {
        if (label.startsWith("Filter food subcategory:")) CategoryMenuTestSupport.reveal(instrumentation, label)
        val matches: (AccessibilityNodeInfo) -> Boolean = { it.text?.toString() == label || it.contentDescription?.toString() == label }
        waitFor { find(matches) != null }
        var node = find(matches)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (matches(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Inventory filter did not reach the expected state", condition())
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
}

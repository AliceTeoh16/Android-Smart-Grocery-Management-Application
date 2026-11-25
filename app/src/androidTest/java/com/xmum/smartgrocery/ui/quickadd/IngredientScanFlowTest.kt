package com.xmum.smartgrocery.ui.quickadd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.databinding.BottomSheetQuickAddBinding
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.ExpiryEstimator
import com.xmum.smartgrocery.utils.GroceryCategories
import com.xmum.smartgrocery.ui.CategoryMenuTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI/database tests use RAM only; visual model responses are controlled fixtures. */
@RunWith(AndroidJUnit4::class)
class IngredientScanFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun reviewSwipesScrollItemsWithoutMovingTheSheet(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openReview(scenario, (1..8).map { food("Cleaning cloth $it", 1, "Household Essentials") })
            fun sheetBounds(): Rect {
                val bounds = Rect()
                scenario.onActivity { activity ->
                    val fragment = allFragments(activity.supportFragmentManager.fragments)
                        .filterIsInstance<IngredientScanBottomSheetDialog>().first()
                    val sheet = fragment.dialog!!.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
                    assertEquals(BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.from(sheet).state)
                    sheet.getGlobalVisibleRect(bounds)
                }
                return bounds
            }
            val original = sheetBounds()
            val title = Rect().also { find(TITLE)!!.getBoundsInScreen(it) }
            val footer = Rect().also { find("Add All to Inventory")!!.getBoundsInScreen(it) }
            val contentTop = title.bottom + 40
            val contentBottom = footer.top - 100
            fun swipe(down: Boolean) {
                val x = original.exactCenterX()
                val start = contentTop + (contentBottom - contentTop) * if (down) 0.2f else 0.8f
                val end = contentTop + (contentBottom - contentTop) * if (down) 0.8f else 0.2f
                val started = SystemClock.uptimeMillis()
                fun inject(action: Int, y: Float) {
                    val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN, start)
                for (step in 1..15) {
                    SystemClock.sleep(16)
                    inject(MotionEvent.ACTION_MOVE, start + (end - start) * step / 15)
                }
                inject(MotionEvent.ACTION_UP, end)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
                assertEquals("Swiping the review content must not move the sheet", original, sheetBounds())
                assertNotNull("The fixed save action must remain visible", find("Add All to Inventory"))
            }
            // A downward swipe at the top must not collapse or dismiss the review.
            swipe(down = true)
            var attempts = 0
            while (find("Cleaning cloth 8")?.isVisibleToUser != true && attempts++ < 16) swipe(down = false)
            assertTrue("Upward swipes must reveal the final item", find("Cleaning cloth 8")?.isVisibleToUser == true)
            swipe(down = true)
            assertTrue("Reviewing and scrolling must not save items", database.groceryDao().getAllItemsOnce().isEmpty())
            scenario.recreate()
            waitFor(TITLE)
            swipe(down = true)
            click("Cancel")
            waitFor("Skip the typing")
        } finally { scenario.close() }
    }

    @Test fun unlistedProductsAndPersonalCareToolsKeepTheirCategoriesWhenReviewedAndSaved(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val detections = listOf(
            VisualFoodItem("Kohlrabi", "Food & Beverage", 3, "Produce"),
            VisualFoodItem("Kombucha", "Food & Beverage", 3, "Beverages"),
            VisualFoodItem("Paneer", "Food & Beverage", 3, "Dairy"),
            VisualFoodItem("Silicone spatula", "Household Essentials", 3),
            VisualFoodItem("Lint roller", "Household Essentials", 3),
            VisualFoodItem("Contact lens solution", "Personal Care", 3),
            VisualFoodItem("Menstrual cup", "Personal Care", 3),
            VisualFoodItem("Makeup sponge", "Personal Care", 3))
        try {
            val model = openReview(scenario, detections)
            assertEquals(listOf("Produce", "Beverages", "Dairy", "Household Essentials", "Household Essentials",
                "Personal Care", "Personal Care", "Personal Care"), model.state.value.items.map { it.category })
            assertTrue(model.state.value.items.drop(3).all { it.expiryDate == null && it.threshold == 1 })
            click("Add All to Inventory")
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == detections.size } }
            for (saved in database.groceryDao().getAllItemsOnce()) {
                val detected = detections.first { it.name == saved.name }
                assertEquals(detected.category, saved.category)
                assertEquals(detected.subcategory, saved.subcategory)
                if (saved.category != "Food & Beverage") assertNull(saved.expiryDate)
            }
        } finally { scenario.close() }
    }

    @Test fun teacupAndTeaReviewAsHouseholdAndBeverageWithCorrectDefaults(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, listOf(
                VisualFoodItem("Tea cup", "Food & Beverage", 2, "Beverages"),
                VisualFoodItem("Cup of tea", "Food & Beverage", 1, "Beverages")))
            val cup = model.state.value.items.first()
            assertEquals("Household Essentials", cup.category)
            assertNull(cup.expiryDate)
            assertEquals(1, cup.threshold)
            val tea = model.state.value.items.last()
            assertEquals("Beverages", tea.category)
            assertEquals(2, tea.threshold)
            click("Add All to Inventory")
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == 2 } }
            val saved = database.groceryDao().getAllItemsOnce()
            val savedCup = saved.first { it.name == "Tea cup" }
            assertEquals("Household Essentials", savedCup.category)
            assertNull(savedCup.subcategory)
            assertNull(savedCup.expiryDate)
            assertEquals(2, savedCup.quantity)
            val savedTea = saved.first { it.name == "Cup of tea" }
            assertEquals("Food & Beverage", savedTea.category)
            assertEquals("Beverages", savedTea.subcategory)
        } finally { scenario.close() }
    }

    @Test fun recognitionCorrectsStaleLabelsBeforeDefaultsAndPersistsEveryCategory(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val expected = linkedMapOf("Tomato" to "Produce", "Salmon" to "Meat & Seafood", "Milk" to "Dairy",
            "Bread" to "Bakery", "Rice" to "Pantry", "Salt" to "Spices", "Orange juice" to "Beverages",
            "Cream crackers" to "Snacks & Sweets", "Frozen chicken" to "Frozen Foods",
            "Plunger" to "Household Essentials", "Shampoo" to "Personal Care")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, expected.keys.map { VisualFoodItem(it, null, 3, "Pantry") })
            assertEquals(expected.values.toList(), model.state.value.items.map { it.category })
            for (row in model.state.value.items) {
                val nonFood = row.category in listOf("Household Essentials", "Personal Care")
                assertEquals(if (nonFood) 1 else 2, row.threshold)
                if (nonFood) assertNull(row.expiryDate)
            }
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            scenario.recreate()
            waitFor(TITLE)
            assertEquals(expected.values.toList(), model.state.value.items.map { it.category })
            click("Add All to Inventory")
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == expected.size } }
            for (item in database.groceryDao().getAllItemsOnce()) {
                assertEquals(expected[item.name], item.categorySelection)
                assertEquals(3, item.quantity)
                if (item.category != "Food & Beverage") {
                    assertNull(item.subcategory)
                    assertNull(item.expiryDate)
                    assertEquals(1, item.threshold)
                }
            }
        } finally { scenario.close() }
    }

    @Test fun singleFoodUsesBatchReviewAndManualItemsKeepReviewedExpiry(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, listOf(food("Broccoli")))
            assertTrue(model.state.value.items.single().selected)
            assertEquals(1, model.state.value.items.single().quantity)
            assertEquals(ExpiryEstimator.estimateExpiryTimestamp("Broccoli", "Produce"), model.state.value.items.single().expiryDate)
            scenario.onActivity { activity ->
                val form = allFragments(activity.supportFragmentManager.fragments).filterIsInstance<QuickAddBottomSheetDialog>().first()
                val fields = QuickAddBottomSheetDialog::class.java.getDeclaredField("_binding").apply { isAccessible = true }
                    .get(form) as BottomSheetQuickAddBinding
                assertTrue("Single foods must bypass form autofill", fields.editItemName.text.isNullOrBlank())
                model.rename(model.state.value.items.first().id, "Potato")
                assertEquals(ExpiryEstimator.estimateExpiryTimestamp("Potato", "Produce"), model.state.value.items.first().expiryDate)
                model.rename(model.state.value.items.first().id, "Broccoli")
            }
            click("+ Add Item")
            eventually { model.state.value.items.size == 2 }
            val customExpiry = System.currentTimeMillis() + 30L * 86400000L
            scenario.onActivity {
                val manual = model.state.value.items.last()
                assertTrue(manual.selected && manual.manuallyAdded)
                assertFalse(model.state.value.canSave)
                model.rename(manual.id, "Mushrooms")
                model.changeCategory(manual.id, "Produce")
                model.adjustQuantity(manual.id, 1)
                model.changeExpiry(manual.id, customExpiry)
                model.rename(manual.id, "Shiitake mushrooms")
                model.changeCategory(manual.id, "Pantry")
                assertEquals("Manual expiry survives later name/category edits", customExpiry, model.state.value.items.last().expiryDate)
                model.changeCategory(manual.id, "Produce")
            }
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            scenario.recreate()
            waitFor(TITLE)
            assertEquals(customExpiry, model.state.value.items.last().expiryDate)
            click("Add All to Inventory")
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == 2 } }
            val added = database.groceryDao().getAllItemsOnce()
            assertEquals(2, added.first { it.name == "Shiitake mushrooms" }.quantity)
            assertEquals(customExpiry, added.first { it.name == "Shiitake mushrooms" }.expiryDate)
        } finally { scenario.close() }
    }

    @Test fun fourVegetablesAppearAsFourCheckedFoods(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val names = listOf("Onion", "Green Bell Pepper", "Tomato", "Potato")
            val model = openReview(scenario, names.map { food(it) })
            assertEquals(names, model.state.value.items.map { it.name })
            assertTrue(model.state.value.items.all { it.selected && it.category == "Produce" && (it.expiryDate ?: 0L) > 0L })
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            click("Cancel")
            waitFor("Skip the typing")
        } finally { scenario.close() }
    }

    @Test fun photoPixelsArePreparedForVisualRecognitionWithoutOcr(): Unit = runBlocking {
        val photo = File(context.cacheDir, "visual-food-test.jpg")
        val bitmap = Bitmap.createBitmap(3200, 2400, Bitmap.Config.RGB_565)
        bitmap.eraseColor(Color.GREEN)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        try {
            val request = VisualFoodRecognition.preparePhoto(context, Uri.fromFile(photo))
            assertEquals("image/jpeg", request.mime)
            val bytes = Base64.decode(request.image, Base64.DEFAULT)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            assertTrue(maxOf(dimensions.outWidth, dimensions.outHeight) <= 1600)
            assertTrue(bytes.size < 4 * 1024 * 1024)
        } finally { photo.delete() }
    }

    @Test fun editsSelectionDeletionRotationAndExplicitBatchSaveRefreshInventory(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, listOf(food("Eggs", 12, "Dairy"), food("Milk", 2, "Dairy"),
                food("Rice", 1, "Pantry"), food("Broccoli")))
            assertTrue("All visual detections start selected", model.state.value.items.all { it.selected })
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            scenario.onActivity {
                val eggs = model.state.value.items.first()
                model.adjustQuantity(eggs.id, 1)
                assertEquals(13, model.state.value.items.first().quantity)
                model.adjustQuantity(eggs.id, -1)
            }
            click("Select Eggs")
            assertFalse(model.state.value.items.first().selected)
            eventually { find("Select Eggs")?.isChecked == false }
            click("Select Eggs")
            eventually { model.state.value.items.first().selected && find("Select Eggs")?.isChecked == true }
            setText("Eggs", "Organic eggs")
            click("Choose Subcategory for Organic eggs")
            click("Select Subcategory: Pantry")
            // Deletion and deselection use the same state callbacks as the corresponding row controls.
            scenario.onActivity {
                model.delete(model.state.value.items.first { it.name == "Milk" }.id)
                model.select(model.state.value.items.last().id, false)
            }
            scenario.recreate()
            waitFor(TITLE)
            scenario.onActivity { activity ->
                val restored = scanModel(activity)
                assertEquals(model, restored)
                assertEquals("Organic eggs", restored.state.value.items.first().name)
                assertEquals("Pantry", restored.state.value.items.first().category)
                assertEquals(12, restored.state.value.items.first().quantity)
                assertFalse(restored.state.value.items.any { it.name == "Milk" })
                assertEquals(2, restored.state.value.selectedCount)
            }
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            click("Add All to Inventory")
            scenario.onActivity { model.addAllToInventory() } // A second submit must not duplicate a committed/in-flight batch.
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == 2 } }
            waitFor("Inventory")
            waitFor("Organic eggs")
            val items = database.groceryDao().getAllItemsOnce()
            assertEquals(setOf("Organic eggs", "Rice"), items.map { it.name }.toSet())
            assertEquals(12, items.first { it.name == "Organic eggs" }.quantity)
            assertTrue(items.all { it.price == 0.0 && it.threshold == 2 && (it.expiryDate ?: 0L) > System.currentTimeMillis() })
            assertEquals(2, items.sumOf { database.groceryDao().getLogsForItem(it.id).first().size })
        } finally { scenario.close() }
    }

    @Test fun transactionFailureRollsBackEveryItemAndLogAndCanRetry(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_milk BEFORE INSERT ON grocery_items WHEN NEW.name = 'Milk' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, listOf(food("Eggs", 12, "Dairy"), food("Milk", 2, "Dairy")))
            click("Add All to Inventory")
            eventually { model.state.value.error?.contains("No items were added") == true }
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM inventory_logs").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
            assertEquals(2, model.state.value.items.size)
            assertFalse(model.state.value.saving)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_milk")
            click("Add All to Inventory")
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == 2 } }
        } finally {
            scenario.close()
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_test_milk")
        }
    }

    @Test fun emptyNamesNoSelectionDeleteAllAndCancellationNeverWriteInventory(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, listOf(food("Milk", 2, "Dairy"), food("Rice", 1, "Pantry")))
            scenario.onActivity {
                model.state.value.items.forEach { row -> model.select(row.id, false) }
                assertFalse(model.state.value.canSave)
                model.addAllToInventory()
                val first = model.state.value.items.first()
                model.select(first.id, true)
                model.rename(first.id, "")
                assertFalse(model.state.value.canSave)
                model.addAllToInventory()
                model.state.value.items.toList().forEach { row -> model.delete(row.id) }
                assertFalse(model.state.value.canSave)
                model.addAllToInventory()
            }
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            click("Cancel")
            waitFor("Skip the typing")
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
        } finally { scenario.close() }
    }

    @Test fun emptyVisualResultsAndNetworkErrorsAllowManualBatchWithoutWrites(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openPicker(scenario)
            lateinit var model: IngredientScanViewModel
            scenario.onActivity { activity ->
                model = scanModel(activity)
                model.setFoodRecognizer { emptyList() }
                model.scan(Uri.EMPTY)
            }
            eventually { !model.state.value.scanning && model.state.value.error != null }
            assertTrue(model.state.value.reviewing)
            assertTrue(model.state.value.items.isEmpty())
            waitFor(TITLE)
            scenario.onActivity {
                model.setFoodRecognizer { throw java.io.IOException("offline") }
                model.scan(Uri.EMPTY)
            }
            eventually { model.state.value.error?.startsWith("Couldn’t reach") == true }
            waitFor("Choose from Gallery")
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            click("+ Add Item")
            eventually { model.state.value.items.size == 1 }
            assertFalse(model.state.value.canSave)
            click("Cancel")
            waitFor("Skip the typing")
        } finally { scenario.close() }
    }

    @Test fun mixedBatchUsesOptionalExpiryDefaultsAndRetainsClearedDatesAfterRecreation(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, listOf(
                food("Tomato", 3, "Produce"), food("Shampoo", 1, "Personal Care"),
                food("Dishwashing detergent", 2, "Household Essentials")))
            val rows = model.state.value.items
            assertNotNull(rows.first { it.name == "Tomato" }.expiryDate)
            assertTrue(rows.filter { it.name != "Tomato" }.all { it.expiryDate == null && it.threshold == 1 })
            assertTrue(model.state.value.canSave)
            scenario.onActivity {
                model.changeExpiry(rows.first().id, null)
                model.rename(rows.first().id, "Fresh Tomato")
                assertNull(model.state.value.items.first().expiryDate)
            }
            scenario.recreate()
            waitFor(TITLE)
            assertTrue(model.state.value.items.all { it.expiryDate == null })
            click("Add All to Inventory")
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == 3 } }
            val added = database.groceryDao().getAllItemsOnce()
            assertEquals(setOf("Food & Beverage", "Personal Care", "Household Essentials"), added.map { it.category }.toSet())
            assertEquals("Produce", added.first { it.name == "Fresh Tomato" }.subcategory)
            assertTrue(added.filter { it.category != "Food & Beverage" }.all { it.subcategory == null && it.threshold == 1 })
            assertTrue(added.all { it.expiryDate == null })
        } finally { scenario.close() }
    }

    @Test fun batchReviewOffersAllNineFoodOptionsAndPersistsTheThreeNewGroups(): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = openReview(scenario, listOf(food("Juice", 1, "Beverages"),
                food("Biscuits", 2, "Snacks & Sweets"), food("Ice cream", 1, "Frozen Foods")))
            assertEquals(listOf("Beverages", "Snacks & Sweets", "Frozen Foods"), model.state.value.items.map { it.category })
            for (label in GroceryCategories.foodSubcategories) {
                click("Choose Subcategory for Juice")
                click("Select Subcategory: $label")
                eventually("Selecting $label did not update the first batch row; current categories: ${model.state.value.items.map { it.category }}") {
                    model.state.value.items.first().category == label
                }
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "nine-food-batch-review.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            click("Choose Subcategory for Juice")
            click("Select Subcategory: Beverages")
            assertTrue(model.state.value.canSave)
            click("Add All to Inventory")
            eventually { runBlocking { database.groceryDao().getAllItemsOnce().size == 3 } }
            val saved = database.groceryDao().getAllItemsOnce()
            assertTrue(saved.all { it.category == "Food & Beverage" })
            assertEquals(setOf("Beverages", "Snacks & Sweets", "Frozen Foods"), saved.map { it.subcategory }.toSet())
        } finally { scenario.close() }
    }

    private fun food(name: String, count: Int? = 1, category: String = "Produce") = VisualFoodItem(name, category, count)

    private fun openReview(scenario: ActivityScenario<MainActivity>, foods: List<VisualFoodItem>): IngredientScanViewModel {
        openPicker(scenario)
        lateinit var model: IngredientScanViewModel
        scenario.onActivity { activity ->
            model = scanModel(activity)
            model.setFoodRecognizer { foods }
            model.scan(Uri.EMPTY)
        }
        waitFor(TITLE)
        return model
    }
    private fun openPicker(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
        click("Add grocery item"); click("Skip the typing")
        waitFor("Take Photo"); waitFor("Choose from Gallery")
    }
    private fun scanModel(activity: MainActivity): IngredientScanViewModel = ViewModelProvider(
        allFragments(activity.supportFragmentManager.fragments).filterIsInstance<IngredientScanBottomSheetDialog>().first()
    )[IngredientScanViewModel::class.java]
    private fun allFragments(fragments: List<Fragment>): List<Fragment> = fragments.flatMap { listOf(it) + allFragments(it.childFragmentManager.fragments) }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitFor(text: String) = eventually { find(text) != null }
    private fun click(text: String) {
        if (text.startsWith("Choose Subcategory for ") || text.startsWith("Select ")) {
            CategoryMenuTestSupport.reveal(instrumentation, text)
        }
        fun actionable(): AccessibilityNodeInfo? {
            var node = find(text) ?: return null
            while (!node.isClickable) node = node.parent ?: return null
            return node.takeIf { it.isEnabled }
        }
        eventually("Could not click $text") { actionable()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        instrumentation.waitForIdleSync()
    }
    private fun setText(old: String, value: String) {
        waitFor(old)
        assertTrue(find(old)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        instrumentation.waitForIdleSync()
    }
    private fun eventually(message: String = "Batch scan condition timed out", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        assertTrue(message, condition())
    }
    companion object { private const val TITLE = "Batch Review Checklist" }
}

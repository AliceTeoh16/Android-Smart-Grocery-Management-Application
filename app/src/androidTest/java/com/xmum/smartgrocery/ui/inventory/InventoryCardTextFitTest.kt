package com.xmum.smartgrocery.ui.inventory

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class InventoryCardTextFitTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun fullDetailsAndUnitsFitNarrowCardsAndLargerSystemFonts() {
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.3f)) for (widthDp in listOf(144, 164, 188)) {
                val base = instrumentation.targetContext
                val configuration = Configuration(base.resources.configuration).apply { this.fontScale = fontScale }
                val context = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_SmartGrocery)
                val parent = FrameLayout(context)
                val adapter = InventoryAdapter({}, {}, {}, {}, {}, {}, {})
                val holder = adapter.onCreateViewHolder(parent, 0)
                val item = GroceryItem(id = 1, name = "100 plus sparkling beverage with a longer product name",
                    quantity = 123456, price = 2.0, category = "Food & Beverage", subcategory = "Beverages",
                    householdId = "home", addedByName = "Alice with a longer family name", unit = "bottles", storageLocation = "Fridge")
                holder.bind(InventoryCardItem(item, item.quantity, null, emptyList()))
                parent.addView(holder.itemView)
                val width = (widthDp * context.resources.displayMetrics.density).roundToInt()
                repeat(3) {
                    parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    parent.layout(0, 0, width, parent.measuredHeight)
                }
                val description = "$widthDp dp / font scale $fontScale"
                for (id in listOf(R.id.text_item_name, R.id.text_item_meta, R.id.text_quantity_unit, R.id.text_quantity, R.id.text_shared_change)) {
                    assertTextFits(holder.itemView.findViewById(id), description)
                }
                val meta = holder.itemView.findViewById<TextView>(R.id.text_item_meta)
                assertEquals("Beverages\nRM 2.00 / bottles\nStorage: Fridge", meta.text.toString())
                val label = holder.itemView.findViewById<TextView>(R.id.text_quantity_unit)
                assertEquals("Qty (bottles)", label.text.toString())
                assertEquals("The unit must not be squeezed into a narrow side column: $description", 1, label.lineCount)
                val controls = holder.itemView.findViewById<View>(R.id.layout_quantity_controls)
                assertTrue(description, controls.top >= label.bottom)
                val decrement = holder.itemView.findViewById<View>(R.id.button_decrement)
                val number = holder.itemView.findViewById<View>(R.id.text_quantity)
                val increment = holder.itemView.findViewById<View>(R.id.button_increment)
                assertTrue(description, decrement.right <= number.left && number.right <= increment.left)
                assertTrue(description, increment.right <= controls.width)
            }
        }
    }

    @Test fun inventoryShowsCompleteBottlesAndStorageDetailsOnTheRealGrid(): Unit = runBlocking {
        val db = (instrumentation as VoiceCommandTestRunner).database
        db.clearAllTables(); db.useTestAccount("card-fit-user")
        db.sharingDao().updateUser(db.accountDao().currentUser()!!.copy(displayName = "Alice"))
        SessionManager(db).createHousehold("Card layout household")
        for (name in listOf("potato", "onion")) db.groceryDao().insert(GroceryItem(name = name,
            quantity = 1, price = 0.0, category = "Food & Beverage", subcategory = "Produce", threshold = 0))
        db.groceryDao().insert(GroceryItem(name = "100 plus", quantity = 1, price = 2.0,
            category = "Food & Beverage", subcategory = "Beverages", threshold = 0, unit = "bottles", storageLocation = "Fridge"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            eventually { if (find("100 plus") != null) true else { scrollForward(); false } }
            // Move to the end of the inventory so the complete final card is visible for review.
            repeat(3) { scrollForward(); Thread.sleep(250) }
            eventually { find("Beverages\nRM 2.00 / bottles\nStorage: Fridge") != null }
            assertNotNull(find("Qty (bottles)"))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "inventory-card-text-fit.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            assertEquals(3, db.groceryDao().getAllItemsOnce().size)
        } finally { scenario.close() }
    }

    private fun assertTextFits(view: TextView, description: String) {
        val layout = view.layout ?: throw AssertionError("No text layout: $description")
        assertTrue(description, layout.lineCount > 0)
        assertEquals("Text must not be clipped: $description / ${view.text}", view.text.length, layout.getLineEnd(layout.lineCount - 1))
        val width = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals(description, 0, layout.getEllipsisCount(line))
            assertTrue("Text must stay inside the card: $description / ${view.text}", layout.getLineRight(line) <= width + 1f)
        }
        assertTrue("Text height must expand: $description / ${view.text}",
            layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun scrollForward(): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let { if (visit(it)) return true }
            return false
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit) == true
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(150) }
        assertTrue("Inventory card details did not become visible", condition())
    }
}

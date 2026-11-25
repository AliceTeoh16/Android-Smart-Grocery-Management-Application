package com.xmum.smartgrocery.ui

import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.quickadd.QuickAddBottomSheetDialog
import com.xmum.smartgrocery.ui.shopping.AddShoppingItemDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real touch gestures against the forms, using the existing in-memory database runner. */
class FormSheetScrollTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val db get() = (instrumentation as VoiceCommandTestRunner).database

    @Test fun shoppingSwipesKeepHeaderAndActionsFixedAndCancelStillCloses(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("scroll-shopping")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { AddShoppingItemDialog.newInstance("Eggs", 12).show(it.supportFragmentManager, "scroll-shopping") }
            eventually { find("Add shopping item") != null }
            verifySwipes(scenario, "Add shopping item", "Add item", "Item name")
            scenario.recreate()
            eventually { find("Add shopping item") != null && find("Eggs") != null }
            verifySwipes(scenario, "Add shopping item", "Add item", "Item name")
            click("Cancel")
            eventually { find("Add shopping item") == null }
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
        } finally { scenario.close() }
    }

    @Test fun inventoryEditScrollsFieldsWithoutMovingActionsOrChangingStoredData(): Unit = runBlocking {
        db.clearAllTables(); db.useTestAccount("scroll-inventory")
        val item = GroceryItem(name = "Eggs", quantity = 12, category = "Food & Beverage", price = 1.0)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { QuickAddBottomSheetDialog.newEditInstance(item).show(it.supportFragmentManager, "scroll-inventory") }
            eventually { find("Edit inventory item") != null }
            verifySwipes(scenario, "Edit inventory item", "Save changes", "Item name", requireScroll = true)
            // Focus the actual Compose field: the action footer must remain visible above the keyboard.
            click("Eggs")
            eventually { find("Save changes")?.isVisibleToUser == true }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            click("Cancel")
            eventually { find("Edit inventory item") == null }
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
        } finally { scenario.close() }
    }

    private fun verifySwipes(scenario: ActivityScenario<MainActivity>, title: String, action: String,
        field: String, requireScroll: Boolean = false) {
        scenario.onActivity { activity ->
            fun all(items: List<Fragment>): List<Fragment> = items.flatMap { listOf(it) + all(it.childFragmentManager.fragments) }
            val form = all(activity.supportFragmentManager.fragments).filterIsInstance<BottomSheetDialogFragment>().last { it.dialog?.isShowing == true }
            val decor = form.dialog!!.window!!.decorView
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(decor.windowToken, 0)
            decor.clearFocus()
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
        screenshot("form-before-swipes.png")
        eventually { find(field) != null && find(action) != null }
        val originalSheet = sheetBounds(scenario)
        val titleBounds = bounds(title)
        val actionBounds = bounds(action)
        val fieldBefore = bounds(field)
        fun swipe(down: Boolean) {
            val top = titleBounds.bottom + 30
            val bottom = actionBounds.top - 30
            val start = top + (bottom - top) * if (down) 0.2f else 0.8f
            val end = top + (bottom - top) * if (down) 0.8f else 0.2f
            val started = SystemClock.uptimeMillis()
            fun send(kind: Int, y: Float) {
                val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), kind, originalSheet.exactCenterX(), y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, start)
            for (step in 1..16) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 16) }
            send(MotionEvent.ACTION_UP, end)
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            assertEquals("Form swipes must not move the sheet", originalSheet, sheetBounds(scenario))
            assertEquals(titleBounds, bounds(title)); assertEquals(actionBounds, bounds(action))
        }
        // Swipe down at the top, then up through the content, then return to the upper fields.
        swipe(true)
        repeat(3) { swipe(false) }
        if (requireScroll) screenshot("inventory-form-scroll.png")
        if (requireScroll) assertTrue("Fields must scroll independently of the footer",
            find(field)?.isVisibleToUser != true || bounds(field) != fieldBefore)
        repeat(4) { swipe(true) }
        eventually { find(field)?.isVisibleToUser == true }
    }

    private fun screenshot(name: String) {
        fun describe(node: AccessibilityNodeInfo): String = buildString {
            val rect = Rect(); node.getBoundsInScreen(rect)
            append("${node.text} ${node.contentDescription} $rect scrollable=${node.isScrollable}\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { append(describe(it)) }
        }
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.txt").writeText(
            instrumentation.uiAutomation.rootInActiveWindow?.let(::describe).orEmpty())
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun sheetBounds(scenario: ActivityScenario<MainActivity>): Rect {
        val result = Rect()
        scenario.onActivity { activity ->
            fun all(items: List<Fragment>): List<Fragment> = items.flatMap { listOf(it) + all(it.childFragmentManager.fragments) }
            val form = all(activity.supportFragmentManager.fragments).filterIsInstance<BottomSheetDialogFragment>().last { it.dialog?.isShowing == true }
            val sheet = form.dialog!!.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
            assertEquals(BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.from(sheet).state)
            sheet.getGlobalVisibleRect(result)
        }
        return result
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun bounds(label: String) = Rect().also { find(label)!!.getBoundsInScreen(it) }
    private fun click(label: String) {
        eventually {
            var node = find(label)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue("Form did not reach expected UI state", condition())
    }
}

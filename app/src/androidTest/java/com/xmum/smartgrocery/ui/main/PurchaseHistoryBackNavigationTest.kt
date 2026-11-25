package com.xmum.smartgrocery.ui.main

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.ui.budget.BudgetFragment
import com.xmum.smartgrocery.ui.budget.BudgetRange
import com.xmum.smartgrocery.ui.budget.BudgetViewModel
import com.xmum.smartgrocery.ui.history.PurchaseHistoryFragment
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.ui.pantrypal.PantryPalFragment
import com.xmum.smartgrocery.ui.pantrypal.ProfileSettingsDialog
import com.xmum.smartgrocery.ui.shopping.ShoppingListFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual Back button and retained destinations with isolated Room data. */
class PurchaseHistoryBackNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun backArrowRestoresHomeShoppingAndBudgetStateAfterRotation() = withScenario { scenario ->
        var origin: Fragment? = null
        scenario.onActivity { origin = current(it); assertTrue(origin is PantryPalFragment) }
        openHistory(scenario)
        clickBack()
        waitOnActivity(scenario) { current(it) === origin }
        scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.card_bottom_nav_container).visibility) }

        scenario.onActivity { it.openPantryAction(PantryAction.Shopping) }
        waitOnActivity(scenario) { current(it) is ShoppingListFragment }
        scenario.onActivity { origin = current(it) }
        openHistory(scenario)
        clickBack()
        waitOnActivity(scenario) { current(it) === origin }
        scenario.onActivity {
            assertEquals(0, it.supportFragmentManager.backStackEntryCount)
            assertTrue(it.findViewById<BottomNavigationView>(R.id.bottom_navigation).menu.findItem(R.id.navigation_shopping).isChecked)
        }

        lateinit var budget: BudgetViewModel
        scenario.onActivity { it.openPantryAction(PantryAction.Budget) }
        waitOnActivity(scenario) { current(it) is BudgetFragment }
        scenario.onActivity {
            budget = ViewModelProvider(current(it)!!)[BudgetViewModel::class.java]
            budget.setRange(BudgetRange.WEEK)
        }
        waitOnActivity(scenario) { budget.activeRange.value == BudgetRange.WEEK }
        openHistory(scenario)
        scenario.recreate()
        waitOnActivity(scenario) { current(it) is PurchaseHistoryFragment }
        clickBack()
        waitOnActivity(scenario) { current(it) is BudgetFragment }
        scenario.onActivity {
            val restored = ViewModelProvider(current(it)!!)[BudgetViewModel::class.java]
            assertSame("Back must restore the original state owner", budget, restored)
            assertEquals(BudgetRange.WEEK, restored.activeRange.value)
            assertEquals(0, it.supportFragmentManager.backStackEntryCount)
            assertTrue(it.findViewById<BottomNavigationView>(R.id.bottom_navigation).menu.findItem(R.id.navigation_budget).isChecked)
        }
    }

    @Test fun profileRemainsOnItsStackForBothToolbarAndSystemBack() = withScenario { scenario ->
        lateinit var profile: ProfileSettingsDialog
        scenario.onActivity { it.showProfileSettings() }
        waitOnActivity(scenario) {
            val found = it.supportFragmentManager.findFragmentByTag(ProfileSettingsDialog.TAG)
            if (found is ProfileSettingsDialog && found.dialog?.isShowing == true) { profile = found; true } else false
        }
        fun openProfileHistory() {
            click("Purchase history")
            waitOnActivity(scenario) { profile.childFragmentManager.findFragmentByTag(PurchaseHistoryFragment.TAG)?.isVisible == true }
        }
        openProfileHistory()
        clickBack()
        waitOnActivity(scenario) { profile.childFragmentManager.backStackEntryCount == 0 }
        eventually { find("Profile & Settings") != null }
        scenario.onActivity {
            assertSame(profile, it.supportFragmentManager.findFragmentByTag(ProfileSettingsDialog.TAG))
            assertTrue(profile.dialog!!.isShowing)
            assertTrue(current(it) is PantryPalFragment)
        }
        openProfileHistory()
        scenario.recreate()
        waitOnActivity(scenario) {
            val restored = it.supportFragmentManager.findFragmentByTag(ProfileSettingsDialog.TAG) as? ProfileSettingsDialog
            if (restored != null) profile = restored
            restored?.childFragmentManager?.findFragmentByTag(PurchaseHistoryFragment.TAG)?.isVisible == true
        }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitOnActivity(scenario) { profile.childFragmentManager.backStackEntryCount == 0 }
        eventually { find("Profile & Settings") != null }
        scenario.onActivity { assertTrue(profile.dialog!!.isShowing); assertTrue(current(it) is PantryPalFragment) }
    }

    @Test fun systemBackRestoresOriginAndChangingTabsDoesNotLeaveStaleHistory() = withScenario { scenario ->
        var shopping: Fragment? = null
        scenario.onActivity { it.openPantryAction(PantryAction.Shopping) }
        waitOnActivity(scenario) { current(it) is ShoppingListFragment }
        scenario.onActivity { shopping = current(it) }
        openHistory(scenario)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitOnActivity(scenario) { current(it) === shopping }
        openHistory(scenario)
        scenario.onActivity { it.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.navigation_budget }
        waitOnActivity(scenario) { current(it) is BudgetFragment }
        scenario.onActivity { assertEquals(0, it.supportFragmentManager.backStackEntryCount) }
        scenario.recreate()
        waitOnActivity(scenario) { current(it) is BudgetFragment }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitOnActivity(scenario) { current(it) is PantryPalFragment }
        scenario.onActivity { assertEquals(0, it.supportFragmentManager.backStackEntryCount) }
    }

    private fun openHistory(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.openPantryAction(PantryAction.PurchaseHistory) }
        waitOnActivity(scenario) { current(it) is PurchaseHistoryFragment && it.supportFragmentManager.backStackEntryCount == 1 }
        eventually { find("Back", true) != null }
    }
    private fun current(activity: MainActivity) = activity.supportFragmentManager.findFragmentById(R.id.fragment_container)
    private fun clickBack() = click("Back", true)
    private fun click(label: String, description: Boolean = false) {
        eventually {
            var node = find(label, description)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }
    private fun find(label: String, description: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((if (description) node.contentDescription else node.text)?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun waitOnActivity(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) = eventually {
        var ready = false; scenario.onActivity { ready = condition(it) }; ready
    }
    private fun eventually(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue("Purchase history did not return to the expected destination", condition())
    }
    private fun withScenario(block: (ActivityScenario<MainActivity>) -> Unit): Unit = runBlocking {
        val db = (instrumentation as VoiceCommandTestRunner).database
        val prefs = instrumentation.targetContext.getSharedPreferences("smart_inventory_auth", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            db.clearAllTables(); db.useTestAccount("history-nav-user")
            prefs.edit().clear().putBoolean("is_signed_in", true).putString("user_id", "history-nav-user")
                .putString("email", "history-nav@example.test").putString("display_name", "History tester").commit()
            val active = ActivityScenario.launch(MainActivity::class.java); scenario = active
            waitOnActivity(active) { current(it) is PantryPalFragment }
            block(active)
        } finally {
            scenario?.close()
            prefs.edit().clear().apply {
                original.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value)
                } }
            }.commit()
        }
    }
}

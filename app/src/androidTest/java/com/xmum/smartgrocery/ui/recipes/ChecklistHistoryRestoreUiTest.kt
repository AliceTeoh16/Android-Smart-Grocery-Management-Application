package com.xmum.smartgrocery.ui.recipes

import com.xmum.smartgrocery.data.local.dao.setBudget
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.useTestAccount
import com.google.gson.Gson
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.data.local.entity.BudgetEntry
import com.xmum.smartgrocery.data.network.AiApiService
import com.xmum.smartgrocery.data.network.dto.RecipeRequest
import com.xmum.smartgrocery.data.network.dto.RecipeResponse
import com.xmum.smartgrocery.data.repository.RecipeRepository
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real navigation/checkbox bindings; all inventory and history writes use the runner's RAM database. */
class ChecklistHistoryRestoreUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val database get() = (instrumentation as VoiceCommandTestRunner).database
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var viewModel: RecipeViewModel
    private val calls = AtomicInteger()
    private fun saved(time: Long, servings: Int) = SavedChecklist(ChecklistHistorySnapshot.newId(time), "Beef Noodle", servings, "pending", time,
        listOf(RecipeRepository.MissingChecklistItem("Noodles", "${servings * 100} g", "Pantry")),
        listOf(RecipeRepository.DeductedInventoryItem("Beef", "${servings * 75}g (Fully Covered)")),
        ingredients = listOf(RecipeRepository.ChecklistIngredient("Noodles", "${servings * 100} g", false),
            RecipeRepository.ChecklistIngredient("Beef", "${servings * 75}g", true)),
        steps = listOf("Boil noodles for $servings servings.", "Add beef and serve."), userPrompt = "Beef noodle for $servings")

    private fun fixture(test: suspend () -> Unit): Unit = runBlocking {
        val prefs = instrumentation.targetContext.getSharedPreferences("ai_checklist_store", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        val searches = instrumentation.targetContext.getSharedPreferences("ai_checklist_history", Context.MODE_PRIVATE)
        val originalSearch = searches.getString("search_history", null)
        try {
            database.clearAllTables()
            database.useTestAccount()
            assertTrue(prefs.edit().clear().putBoolean("room_history_snapshot_migrated", true).commit())
            test()
        } finally {
            scenario?.close(); scenario = null
            val edit = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key,value); is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value); is Long -> edit.putLong(key,value)
            } }
            assertTrue(edit.commit())
            assertTrue(searches.edit().apply { if (originalSearch == null) remove("search_history") else putString("search_history", originalSearch) }.commit())
        }
    }
    private fun open(api: AiApiService = object : AiApiService {
        override suspend fun generateRecipes(request: RecipeRequest): RecipeResponse { calls.incrementAndGet(); error("History must never request AI") }
    }) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            viewModel = ViewModelProvider(it)[RecipeViewModel::class.java]
            viewModel.recipeRepository = RecipeRepository(api)
            it.openPantryAction(PantryAction.AiChecklist)
        }
        visit("Checklist History")
    }
    @Test fun duplicateNamesReopenIndependentlyWithExactStepsAndChecksAndNoAiOrStockUpdates() = fixture {
        val base = saved(1000L, 2)
        val older = base.copy(uncheckedInventoryItemKeys = listOf(base.inventoryDeducted.single().inventorySelectionKey()),
            uncheckedMissingItemKeys = listOf("noodles|200 g|pantry"))
        val newer = saved(2000L, 4)
        database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(older))
        database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(newer))
        open()
        click("Checklist History"); visit(openLabel(older)); visit(openLabel(newer))
        assertNull(find("Required Ingredients:")); capture("cards")
        click(openLabel(older))
        eventually { state().checklistResult?.servings == 2 && !state().selectableInventoryItems.single().isSelected }
        assertFalse(state().selectableMissingItems.single().isSelected)
        assertEquals(older.ingredients, state().checklistResult!!.ingredients)
        assertEquals(older.userPrompt, state().userPrompt)
        visit(older.userPrompt!!)
        assertNull(find("Close history"))
        click("View Recipe Steps"); visit(older.steps!!.first()); click("Hide Recipe Steps")
        click("Use Beef")
        eventually { runBlocking {
            ChecklistHistorySnapshot.decode(database.checklistHistoryDao().getChecklistHistoryById(older.id)!!).uncheckedInventoryItemKeys.orEmpty().isEmpty()
        } }
        click("Checklist History"); click(openLabel(newer))
        eventually { state().checklistResult?.servings == 4 }
        assertTrue(state().selectableInventoryItems.single().isSelected)
        assertTrue(state().selectableMissingItems.single().isSelected)
        assertEquals(newer.steps, state().checklistResult!!.steps)
        scenario!!.close(); scenario = null
        // No preference payload exists. A new ViewModel must use only the stored Room snapshot.
        open(); eventually { state().checklistResult?.servings == 4 }
        click("Checklist History"); click(openLabel(older))
        eventually { state().checklistResult?.servings == 2 && state().selectableInventoryItems.single().isSelected }
        assertFalse(state().selectableMissingItems.single().isSelected)
        click("View Recipe Steps"); visit(older.steps!!.first()); capture("restored")
        assertEquals(0, calls.get())
        assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
        assertEquals(2, database.checklistHistoryDao().getCount())
    }
    @Test fun generationPersistsFullPayloadAndHistoryCancelsInFlightGenerationWithoutAnotherRequest() = fixture {
        val response = Gson().fromJson("""{"dish_name":"Beef Noodle","servings":2,
            "missing_checklist":[{"name":"Noodles","missing_qty":"200 g","category":"Pantry"}],
            "inventory_deducted":[],"ingredients":[{"name":"Noodles","amount":"200 g","is_in_inventory":false}],
            "steps":["Boil noodles and serve."]}""", RecipeResponse::class.java)
        val waiting = CompletableDeferred<Unit>()
        val api = object : AiApiService {
            override suspend fun generateRecipes(request: RecipeRequest): RecipeResponse {
                val count = calls.incrementAndGet()
                if (count == 3) waiting.await()
                return response
            }
        }
        open(api)
        instrumentation.runOnMainSync { viewModel.generateChecklist("Beef noodle for 2") }
        eventually { state().checklistResult != null && !state().isLoading }
        val first = database.checklistHistoryDao().getAllChecklistHistorySync().single()
        assertEquals(response.steps, ChecklistHistorySnapshot.decode(first).steps)
        assertEquals("Beef noodle for 2", ChecklistHistorySnapshot.decode(first).userPrompt)
        instrumentation.runOnMainSync { viewModel.generateChecklist("Beef noodle again") }
        eventually { runBlocking { database.checklistHistoryDao().getCount() == 2 } && !state().isLoading }
        val second = database.checklistHistoryDao().getAllChecklistHistorySync().first()
        assertNotEquals(first.id, second.id); assertEquals(first.dishName,second.dishName)
        instrumentation.runOnMainSync { viewModel.generateChecklist("Another request") }
        eventually { calls.get() == 3 && state().isLoading }
        click("Checklist History"); click(openLabel(ChecklistHistorySnapshot.decode(first)))
        eventually { !state().isLoading && state().userPrompt == "Beef noodle for 2" }
        waiting.complete(Unit)
        // One idle round plus a lifecycle recreation catches late-response replacement.
        scenario!!.recreate(); visit("Checklist History")
        eventually { state().userPrompt == "Beef noodle for 2" }
        assertEquals(3, calls.get())
        assertEquals(2, database.checklistHistoryDao().getCount())
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
    }
    @Test fun cookedAndCancelledSnapshotsRestoreTheirOriginalChecksAsReadOnly() = fixture {
        val cooked = saved(3000L, 6).let { it.copy(status = "cooked",
            uncheckedInventoryItemKeys = listOf(it.inventoryDeducted.single().inventorySelectionKey())) }
        val cancelled = saved(4000L, 8).copy(status = "cancelled", uncheckedMissingItemKeys = listOf("noodles|800 g|pantry"))
        for (item in listOf(cooked,cancelled)) database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(item))
        open()
        for (item in listOf(cooked,cancelled)) {
            click("Checklist History"); click(openLabel(item))
            eventually { state().activeChecklistStatus == item.status }
            assertEquals(item.steps,state().checklistResult!!.steps)
            assertEquals(item.uncheckedInventoryItemKeys.orEmpty().isEmpty(),state().selectableInventoryItems.single().isSelected)
            assertEquals(item.uncheckedMissingItemKeys.orEmpty().isEmpty(),state().selectableMissingItems.single().isSelected)
            assertFalse(state().selectableMissingItems.single().isEditable)
            visit("Use Beef"); assertFalse(find("Use Beef")!!.isEnabled)
            assertNull(find("Mark as cooked"))
        }
        assertEquals(0,calls.get())
        assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
    }
    @Test fun clearRequiresConfirmationDeletesOnlyHistoryAndStaysEmptyAfterReopen() = fixture {
        val old = saved(1000L,2); val newer = saved(2000L,4)
        for (item in listOf(old,newer)) database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(item))
        database.groceryDao().insertWithLog(GroceryItem(name="Eggs", quantity=5, price=2.0, category="Dairy", threshold=0, expiryDate=1900000000000L))
        database.shoppingListDao().insertManual(ShoppingListItem(itemName="Milk",category="Dairy",quantityNeeded=2,estimatedUnitPrice=3.0))
        database.budgetDao().setBudget(BudgetEntry("2026-10",200.0))
        val otherTables = otherTableRows()
        open(); click("Checklist History"); visit(openLabel(newer))
        click("Clear All History"); visit("Clear all history?")
        assertEquals(2,database.checklistHistoryDao().getCount())
        capture("clear-confirmation")
        click("Cancel"); visit(openLabel(newer))
        assertEquals(2,database.checklistHistoryDao().getCount())
        click("Clear All History"); click("Clear history")
        visit("No checklist history yet")
        eventually { state().savedChecklists.isEmpty() && !state().isClearingHistory && state().checklistResult == null }
        assertEquals(0,database.checklistHistoryDao().getCount())
        assertEquals(otherTables,otherTableRows())
        capture("cleared")
        val prefs = instrumentation.targetContext.getSharedPreferences("ai_checklist_store",Context.MODE_PRIVATE)
        assertNull(prefs.getString("active_history_id",null))
        // A leftover legacy payload must never resurrect a deliberately cleared history.
        prefs.edit().putString("saved_checklists",Gson().toJson(listOf(old))).commit()
        scenario!!.close(); scenario=null; open()
        click("Checklist History"); visit("No checklist history yet")
        assertEquals(0,database.checklistHistoryDao().getCount())
        assertNull(state().checklistResult)
        assertEquals(0,calls.get())
    }
    @Test fun clearCancelsPendingGenerationAndAllowsFreshGenerationAfterwards() = fixture {
        val old=saved(1000L,2)
        database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(old))
        val waiting=CompletableDeferred<Unit>()
        val response=Gson().fromJson("""{"dish_name":"Fresh noodles","servings":1,
            "missing_checklist":[{"name":"Noodles","missing_qty":"100g","category":"Pantry"}],
            "inventory_deducted":[],"ingredients":[{"name":"Noodles","amount":"100g","is_in_inventory":false}],
            "steps":["Boil noodles and serve."]}""",RecipeResponse::class.java)
        open(object : AiApiService {
            override suspend fun generateRecipes(request: RecipeRequest): RecipeResponse {
                if (calls.incrementAndGet()==1) waiting.await()
                return response
            }
        })
        instrumentation.runOnMainSync { viewModel.generateChecklist("Pending request") }
        eventually { calls.get()==1 && state().isLoading }
        click("Checklist History"); click("Clear All History"); click("Clear history")
        visit("No checklist history yet"); waiting.complete(Unit)
        click("Close history")
        eventually { !state().isLoading && state().checklistResult == null }
        assertEquals(0,database.checklistHistoryDao().getCount())
        instrumentation.runOnMainSync { viewModel.generateChecklist("Fresh request") }
        eventually { state().checklistResult?.dishName=="Fresh noodles" && !state().isLoading }
        assertEquals(1,database.checklistHistoryDao().getCount())
        assertNotEquals(old.id,database.checklistHistoryDao().getAllChecklistHistorySync().single().id)
        assertEquals(2,calls.get())
    }
    @Test fun failedClearKeepsSavedRecordsAndCurrentChecklist() = fixture {
        val old=saved(1000L,2)
        database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(old))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_clear_history BEFORE DELETE ON checklist_history BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
        try {
            open(); click("Checklist History"); click("Clear All History"); click("Clear history")
            visit(openLabel(old))
            eventually { !state().isClearingHistory }
            assertEquals(1,database.checklistHistoryDao().getCount())
            assertEquals(2,state().checklistResult!!.servings)
            assertEquals(0,calls.get())
        } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_clear_history") }
    }
    private fun otherTableRows(): Map<String,List<List<String?>>> =
        listOf("grocery_items","shopping_list_items","inventory_logs","budget_entries").associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
                buildList { while(cursor.moveToNext()) add((0 until cursor.columnCount).map { if(cursor.isNull(it)) null else cursor.getString(it) }) }
            }
        }
    @Test fun globalPendingShoppingStatusUpdatesAcrossHistoryAndPurchaseWithQuantityBelowName() = fixture {
        val older=saved(1000L,2); val newer=saved(2000L,4)
        for (item in listOf(older,newer)) database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(item))
        val dao=database.shoppingListDao()
        val noodles=dao.insertManual(ShoppingListItem(itemName="Noodles",category="Pantry",quantityNeeded=200,
            quantityLabel="200g",estimatedUnitPrice=0.0,assignedTo="Household")).toInt()
        val beef=dao.insertManual(ShoppingListItem(itemName="Beef",category="Meat",quantityNeeded=150,
            quantityLabel="150g",estimatedUnitPrice=0.0,assignedTo="Me")).toInt()
        dao.insertManual(ShoppingListItem(itemName="Noodles",category="Pantry",quantityNeeded=200,
            quantityLabel="200g",estimatedUnitPrice=0.0,assignedTo="Me",isChecked=true))
        open()
        eventually { state().selectableMissingItems.singleOrNull()?.isAddedToShopping==true && state().selectableInventoryItems.single().isAddedToShopping }
        visit("Noodles"); visit("400 g"); visit("Added to shopping list")
        val name=android.graphics.Rect(); val quantity=android.graphics.Rect(); val badge=android.graphics.Rect()
        find("Noodles")!!.getBoundsInScreen(name); find("400 g")!!.getBoundsInScreen(quantity)
        find("Added to shopping list")!!.getBoundsInScreen(badge)
        assertTrue(quantity.top>=name.bottom); assertEquals(name.left,quantity.left); assertTrue(badge.left>=quantity.right)
        assertTrue(state().selectableMissingItems.single().isEditable)
        capture("global-shopping-status")
        click("Select Noodles"); eventually { !state().selectableMissingItems.single().isSelected }
        click("Select Noodles"); eventually { state().selectableMissingItems.single().isSelected }
        click("Checklist History"); click(openLabel(older))
        eventually { state().checklistResult?.servings==2 && state().selectableMissingItems.single().isAddedToShopping }
        assertTrue(state().selectableInventoryItems.single().isAddedToShopping)
        assertTrue(ChecklistHistorySnapshot.decode(database.checklistHistoryDao().getChecklistHistoryById(older.id)!!).addedShoppingItemKeys.orEmpty().isEmpty())
        dao.setChecked(noodles,true)
        eventually { !state().selectableMissingItems.single().isAddedToShopping }
        dao.getShoppingListItems().first().first { it.id==beef }.let { dao.delete(it) }
        eventually { !state().selectableInventoryItems.single().isAddedToShopping }
        dao.insertManual(ShoppingListItem(itemName="Noodles",category="Pantry",quantityNeeded=100,
            quantityLabel="100g",estimatedUnitPrice=0.0,assignedTo="Me"))
        eventually { state().selectableMissingItems.single().isAddedToShopping }
        assertEquals(0,calls.get())
    }
    @Test fun addedIngredientsCanBeSelectedAgainAndAccumulateFromAnyHistoryWithinChosenList() = fixture {
        val older=saved(1000L,2); val newer=saved(2000L,2)
        for (item in listOf(older,newer)) database.checklistHistoryDao().saveWithPruning(ChecklistHistorySnapshot.encode(item))
        open(); click("Use Beef")
        click("Add Selected (1) to Shopping List"); click("Personal Shopping List")
        eventually { state().selectableMissingItems.single().isAddedToShopping && !state().isAddingToShopping }
        val first=database.shoppingListDao().getShoppingListItems().first().single()
        assertEquals("200 g",first.quantityLabel)
        click("Select Noodles"); click("Add Selected (1) to Shopping List"); click("Personal Shopping List")
        eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().single().quantityLabel=="400 g" } && !state().isAddingToShopping }
        assertEquals(first.id,database.shoppingListDao().getShoppingListItems().first().single().id)
        click("Checklist History"); click(openLabel(older)); click("Use Beef")
        eventually { state().selectableMissingItems.single().isAddedToShopping && state().selectedShoppingCount==1 }
        click("Add Selected (1) to Shopping List"); click("Personal Shopping List")
        eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().single().quantityLabel=="600 g" } && !state().isAddingToShopping }
        click("Select Noodles"); click("Add Selected (1) to Shopping List"); click("Household / Shared Shopping List")
        eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().singleOrNull()?.assignedTo == "Household" } && !state().isAddingToShopping }
        assertEquals("200 g", database.shoppingListDao().getShoppingListItems().first().single().quantityLabel)
        assertTrue(state().selectableMissingItems.single().isAddedToShopping)
        assertTrue(state().selectableMissingItems.single().isEditable)
        SessionManager(database).selectHousehold(null)
        val personal = database.shoppingListDao().getShoppingListItems().first().single()
        assertEquals("Me", personal.assignedTo)
        assertEquals("600 g", personal.quantityLabel)
        assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
        assertEquals(0,calls.get())
    }
    // StateFlow is thread-safe; ActivityScenario.onActivity waits for global UI idleness first,
    // which would wait on the very in-flight request that this test needs to cancel.
    private fun state(): ChecklistUiState = viewModel.uiState.value
    private fun openLabel(item: SavedChecklist) = "Open ${item.dishName}, ${item.servings} servings, ${java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(item.createdAt))}"
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis()+20000
        while (System.currentTimeMillis()<deadline) { if (condition()) return; Thread.sleep(150) }
        assertTrue("History restoration condition timed out",condition())
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString()==text || node.contentDescription?.toString()==text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun click(text: String) = visit(text,true)
    private fun visit(text: String, click: Boolean = false) {
        android.util.Log.i("HistoryRestoreTest", "${if (click) "Click" else "Find"}: $text")
        var attempts=0
        eventually {
            val found=find(text)
            if (found!=null) {
                if (!click) true else {
                    var node: AccessibilityNodeInfo = found
                    while (!node.isClickable && node.parent!=null) node=node.parent
                    node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } else {
                val direction=if (attempts++ % 12 < 6) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                fun scroll(node: AccessibilityNodeInfo): Boolean {
                    if (node.isScrollable && node.performAction(direction)) return true
                    for (i in 0 until node.childCount) node.getChild(i)?.let { if (scroll(it)) return true }
                    return false
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::scroll); false
            }
        }
        // An intentionally pending AI request animates a progress bar behind the dialog.
        // Waiting for global Looper idleness can block forever on a slow emulator; assertions above are bounded.
        Thread.sleep(150)
    }
    private fun capture(name: String) {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(instrumentation.targetContext.cacheDir,"history-restore-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
}

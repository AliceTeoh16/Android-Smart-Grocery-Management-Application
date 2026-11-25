package com.xmum.smartgrocery.ui.recipes

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.network.AiApiService
import com.xmum.smartgrocery.data.network.dto.RecipeRequest
import com.xmum.smartgrocery.data.network.dto.RecipeResponse
import com.xmum.smartgrocery.data.repository.RecipeRepository
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.main.VoiceCommandTestRunner
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real UI and unchanged repository/view-model flow, with a controlled HTTP error and no paid API calls. */
class ChecklistErrorUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db get() = (instrumentation as VoiceCommandTestRunner).database
    private val prompt = "chicken rice for 1 pax"
    private val raw = """{"error":"AI checklist generation failed.","code":503,"detail":"{\"error\":{\"code\":503,\"message\":\"This model is currently experiencing high demand.\",\"status\":\"UNAVAILABLE\"}}"}"""

    @Test fun busyMessageRetainsRequestAndManualRetryUsesTheOriginalGenerationFlow(): Unit = runBlocking {
        val preferences = context.getSharedPreferences("ai_checklist_store", Context.MODE_PRIVATE)
        val originalPreferences = preferences.all.toMap()
        val requests = CopyOnWriteArrayList<RecipeRequest>()
        lateinit var model: RecipeViewModel
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            db.clearAllTables(); db.useTestAccount("busy-ui")
            preferences.edit().clear().putBoolean("room_history_snapshot_migrated", true).commit()
            val active = ActivityScenario.launch(MainActivity::class.java); scenario = active
            active.onActivity {
                model = ViewModelProvider(it)[RecipeViewModel::class.java]
                model.recipeRepository = RecipeRepository(object : AiApiService {
                    override suspend fun generateRecipes(request: RecipeRequest): RecipeResponse {
                        requests.add(request)
                        if (requests.size == 1) throw HttpException(Response.error<RecipeResponse>(503,
                            ResponseBody.create(MediaType.parse("application/json"), raw)))
                        return Gson().fromJson("""{"dish_name":"Chicken rice","servings":1,
                            "missing_checklist":[{"name":"Chicken","missing_qty":"200 g","category":"Meat & Seafood"}],
                            "inventory_deducted":[],"ingredients":[{"name":"Chicken","amount":"200 g","is_in_inventory":false}],
                            "steps":["Cook the chicken thoroughly and serve."]}""", RecipeResponse::class.java)
                    }
                })
                it.openPantryAction(PantryAction.AiChecklist)
            }
            eventually { find("What would you like to cook?") != null }
            active.onActivity { model.setPrompt(prompt) }
            eventually { find(prompt) != null }
            click("Generate checklist")
            eventually { find("AI service is busy") != null }
            assertNotNull(find(ChecklistErrorMessage.from(raw).message))
            assertNotNull(find("Try again")); assertNotNull(find(prompt))
            assertNull(find("Checklist failed")); assertNull(find(raw))
            val visibleText = instrumentation.uiAutomation.rootInActiveWindow?.let(::texts).orEmpty()
            assertFalse(visibleText.contains("UNAVAILABLE")); assertFalse(visibleText.contains("503"))
            Thread.sleep(500)
            assertEquals("Presentation must not add automatic retries", 1, requests.size)
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
            assertTrue(db.checklistHistoryDao().getAllChecklistHistory().first().isEmpty())
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "friendly-checklist-error.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            active.recreate()
            eventually { find("AI service is busy") != null && find(prompt) != null }
            assertEquals(1, requests.size)
            click("Try again")
            eventually { find("Chicken rice") != null && find("AI service is busy") == null }
            assertEquals(2, requests.size)
            assertEquals("Retry must submit the same request and settings", requests[0], requests[1])
            assertEquals(prompt, requests[1].userPrompt)
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
            assertEquals(1, db.checklistHistoryDao().getAllChecklistHistory().first().size)
        } finally {
            scenario?.close()
            preferences.edit().clear().apply {
                originalPreferences.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value); is Long -> putLong(key, value)
                } }
            }.commit()
        }
    }
    private fun texts(node: AccessibilityNodeInfo): String = buildString {
        append(node.text?.toString().orEmpty()); append(' '); append(node.contentDescription?.toString().orEmpty())
        for (i in 0 until node.childCount) node.getChild(i)?.let { append(' '); append(texts(it)) }
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
        assertTrue("Checklist error UI did not reach the expected state: " +
            instrumentation.uiAutomation.rootInActiveWindow?.let(::texts), condition())
    }
}

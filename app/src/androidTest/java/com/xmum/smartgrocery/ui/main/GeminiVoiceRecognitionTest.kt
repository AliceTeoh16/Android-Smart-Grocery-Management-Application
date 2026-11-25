package com.xmum.smartgrocery.ui.main

import android.Manifest
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.useTestAccount
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.VoiceLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Real recording-sheet/review lifecycle and Room dispatch; all groceries are held only in RAM. */
class GeminiVoiceRecognitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database get() = (instrumentation as VoiceCommandTestRunner).database
    private val preferences get() = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
    private class Capture(private val audio: ByteArray = VoiceWaveAudio.encode(ByteArray(VoiceWaveAudio.MIN_PCM_BYTES) { (it % 31).toByte() })) : VoiceAudioCapture {
        val result = CompletableDeferred<ByteArray>()
        @Volatile var cancelled = false
        override suspend fun record(onReady: () -> Unit, onLevel: (Float, Long) -> Unit): ByteArray {
            onReady(); onLevel(0.1f, 1000); return result.await()
        }
        override fun finish() { result.complete(audio) }
        override fun cancel() { cancelled = true; result.cancel() }
    }
    private fun fixture(block: suspend (ActivityScenario<MainActivity>, Int, Int, MutableList<Capture>) -> Unit): Unit = runBlocking {
        database.clearAllTables()
        database.useTestAccount()
        val eggs = database.groceryDao().insert(GroceryItem(name = "Eggs", quantity = 5, price = 2.0,
            category = "Other", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val milk = database.groceryDao().insert(GroceryItem(name = "Milk", quantity = 2, price = 3.0,
            category = "Dairy", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val original = preferences.getString("language", null)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val captures = mutableListOf<Capture>()
        scenario.onActivity { activity -> activity.voiceCaptureFactory = { Capture().also { captures += it } } }
        try { block(scenario, eggs, milk, captures) }
        finally {
            scenario.close()
            instrumentation.waitForIdleSync()
            preferences.edit().apply { if (original == null) remove("language") else putString("language", original) }.commit()
        }
    }
    private fun listen(scenario: ActivityScenario<MainActivity>, shopping: Boolean, changeScreen: Boolean = false) {
        scenario.onActivity {
            it.openPantryAction(if (shopping) PantryAction.Shopping else PantryAction.Inventory)
            it.triggerVoiceInput()
        }
        if (!shopping) click("Continue with voice input")
        waitFor("Done speaking")
        if (changeScreen) scenario.onActivity {
            it.openPantryAction(if (shopping) PantryAction.Inventory else PantryAction.Shopping)
        }
    }

    @Test fun englishChineseAndMalayReachReviewOnlyAndRememberTheirLanguage(): Unit = fixture { scenario, eggs, milk, _ ->
        listOf(Triple("Add two bottles of milk", VoiceLanguage.ENGLISH, false),
            Triple("我买了两个鸡蛋和一瓶牛奶", VoiceLanguage.CHINESE, false),
            Triple("Beli dua botol susu dan satu sayap ayam", VoiceLanguage.MALAY, true)).forEach { (text, language, shopping) ->
            scenario.onActivity { it.transcribeVoiceClip = { wav, _ ->
                assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII)); VoiceTranscription(text, language)
            } }
            listen(scenario, shopping)
            assertNull(find("Check voice command"))
            click("Done speaking")
            waitFor("Check voice command")
            waitFor(text)
            assertEquals(language.tag, preferences.getString("language", null))
            assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
            assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Cancel")
        }
    }

    @Test fun confirmationUsesOriginalScreenAndNewItemsStayEditable(): Unit = fixture { scenario, eggs, milk, _ ->
        scenario.onActivity { it.transcribeVoiceClip = { _, _ -> VoiceTranscription("Add 2 bottles of milk", VoiceLanguage.ENGLISH) } }
        listen(scenario, shopping = false, changeScreen = true)
        click("Done speaking"); waitFor("Add 2 × milk")
        assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
        click("Confirm")
        eventually { runBlocking { database.groceryDao().getItemById(milk)?.quantity == 4 } }
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())

        scenario.onActivity { it.transcribeVoiceClip = { _, _ -> VoiceTranscription("买两瓶牛奶和一个鸡翅", VoiceLanguage.CHINESE) } }
        listen(scenario, shopping = true, changeScreen = true)
        click("Done speaking"); waitFor("Add 2 × milk"); waitFor("Add 1 × chicken wing")
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
        click("Me only")
        click("Confirm")
        eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == 2 } }
        assertEquals(4, database.groceryDao().getItemById(milk)!!.quantity)

        scenario.onActivity { it.transcribeVoiceClip = { _, _ -> VoiceTranscription("Add 2 tomatoes", VoiceLanguage.ENGLISH) } }
        listen(scenario, false); click("Done speaking"); waitFor("Check voice command")
        eventually {
            var loaded = false
            scenario.onActivity { activity ->
                loaded = (activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG)
                    as? VoiceCommandConfirmationDialog)?.newItemDrafts?.isNotEmpty() == true
            }
            loaded
        }
        scenario.onActivity { activity ->
            val review = activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) as VoiceCommandConfirmationDialog
            assertEquals("2", review.newItemDrafts!!.single().quantity)
        }
        scrollTo("Save & confirm")
        scrollTo("Cancel")
        click("Cancel")
        assertEquals(2, database.groceryDao().getAllItems().first().size)
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
    }

    @Test fun cancellingRecordingOrLeavingTheAppStopsCaptureWithoutTranscription(): Unit = fixture { scenario, eggs, _, captures ->
        val calls = AtomicInteger()
        scenario.onActivity { it.transcribeVoiceClip = { _, _ -> calls.incrementAndGet(); VoiceTranscription("Add 2 eggs", VoiceLanguage.ENGLISH) } }
        listen(scenario, false); click("Cancel")
        eventually { captures.last().cancelled }
        assertEquals(0, calls.get())
        listen(scenario, false)
        scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(captures.last().cancelled)
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(0, calls.get())
        scenario.onActivity { assertNull(it.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG)) }
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
    }

    @Test fun cancellationDiscardsLateTranscriptionAndErrorsCanRetry(): Unit = fixture { scenario, eggs, _, _ ->
        scenario.onActivity { it.transcribeVoiceClip = { _, _ ->
            withContext(NonCancellable) { delay(1000); VoiceTranscription("Add 2 eggs", VoiceLanguage.ENGLISH) }
        } }
        listen(scenario, false); click("Done speaking"); waitFor("Transcribing with Gemini…"); click("Cancel")
        delay(1300)
        scenario.onActivity { assertNull(it.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG)) }
        val calls = AtomicInteger()
        scenario.onActivity { it.transcribeVoiceClip = { _, _ ->
            if (calls.incrementAndGet() == 1) throw IOException("Test connection unavailable. Type or try again.")
            VoiceTranscription("Add 2 eggs", VoiceLanguage.ENGLISH)
        } }
        listen(scenario, false); click("Done speaking"); waitFor("Type Voice Command")
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
        click("Try voice again"); click("Done speaking"); waitFor("Add 2 × eggs"); click("Cancel")
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
    }

    @Test fun emptyTranscriptsAreNotCommandsAndUncertainLanguageDoesNotReplacePreference(): Unit = fixture { scenario, eggs, _, _ ->
        preferences.edit().putString("language", "zh-CN").commit()
        scenario.onActivity { it.transcribeVoiceClip = { _, _ -> VoiceTranscription("", null) } }
        listen(scenario, false); click("Done speaking"); waitFor("Type Voice Command"); click("Cancel")
        scenario.onActivity { it.transcribeVoiceClip = { _, _ -> VoiceTranscription("Add 2 eggs", null) } }
        listen(scenario, false); click("Done speaking"); waitFor("Add 2 × eggs")
        waitFor(VoiceCommandConfirmationDialog.LANGUAGE_UNCONFIRMED_NOTICE)
        assertEquals("zh-CN", preferences.getString("language", null))
        scenario.recreate(); waitFor("Check voice command"); waitFor("Add 2 × eggs")
        click("Cancel")
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
    }

    /** Opt-in: synthetic speech through the real Android HTTP client and Gemini, never audio injection. */
    @Test fun liveBackendEnglishAndMandarinAreReviewOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_gemini_voice") == "true")
        fixture { scenario, eggs, milk, _ ->
            for ((file, shopping, language) in listOf(Triple("en.wav", false, VoiceLanguage.ENGLISH),
                Triple("zh.wav", true, VoiceLanguage.CHINESE))) {
                val audio = instrumentation.context.assets.open("voice/$file").use { it.readBytes() }
                scenario.onActivity { activity -> activity.voiceCaptureFactory = { Capture(audio) } }
                listen(scenario, shopping); click("Done speaking")
                eventually(45000) { find("Check voice command") != null }
                waitFor(if (shopping) "Add 2 × milk" else "Add 2 × eggs")
                if (shopping) waitFor("Add 1 × chicken wing")
                assertEquals(language.tag, preferences.getString("language", null))
                assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
                assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
                assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
                click("Cancel")
            }
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { search(it) }
    }
    private fun waitFor(text: String) = eventually { find(text) != null }
    private fun scrollTo(text: String) {
        fun scroll(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
            return false
        }
        repeat(12) {
            if (find(text) != null) return
            instrumentation.uiAutomation.rootInActiveWindow?.let { scroll(it) }
            Thread.sleep(150)
        }
        waitFor(text)
    }
    private fun click(text: String) {
        eventually {
            var node = find(text) ?: return@eventually false
            while (!node.isClickable) node = node.parent ?: return@eventually false
            node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun eventually(timeoutMs: Long = 12000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Voice condition timed out", condition())
    }
}

package com.xmum.smartgrocery.ui.main

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.VoiceRecognitionFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SpeechRecognizer IPC and app callbacks, scripted service metadata, and only RAM groceries. */
@RunWith(AndroidJUnit4::class)
class MultilingualVoiceRecognitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database get() = (instrumentation as VoiceCommandTestRunner).database
    private val testPackage = "com.xmum.smartgrocery.test"

    private fun fixture(block: suspend (ActivityScenario<MainActivity>, Int, Int) -> Unit): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        database.clearAllTables()
        val eggs = database.groceryDao().insert(GroceryItem(name = "Eggs", quantity = 5, price = 2.0,
            category = "Other", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val milk = database.groceryDao().insert(GroceryItem(name = "Milk", quantity = 2, price = 3.0,
            category = "Dairy", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(testPackage, Manifest.permission.RECORD_AUDIO)
        val preferences = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
        val originalLanguage = preferences.getString("language", null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.useDeviceVoiceRecognition = true
            activity.speechRecognizerFactory = { SpeechRecognizer.createSpeechRecognizer(it,
                ComponentName(testPackage, "com.xmum.smartgrocery.ui.main.ScriptedRecognitionService")) }
        }
        try {
            block(scenario, eggs, milk)
        } finally {
            scenario.close()
            instrumentation.waitForIdleSync()
            preferences.edit().apply { if (originalLanguage == null) remove("language") else putString("language", originalLanguage) }.commit()
        }
    }

    private fun script(text: String, language: String, report: Boolean = true, confidence: Int = 3, switchResult: Int = 1, error: Int = 0) {
        val result = context.contentResolver.call(Uri.parse("content://$testPackage.speech"), "script", null,
            Bundle().apply {
                putString("text", text)
                putString("language", language)
                putBoolean("report_language", report)
                putInt("confidence", confidence)
                putInt("switch_result", switchResult)
                putInt("error", error)
            })
        assertTrue("Script delivered to test service", result?.getBoolean("ready") == true)
    }

    private fun listen(scenario: ActivityScenario<MainActivity>, shopping: Boolean, changeScreen: Boolean = false) {
        scenario.onActivity { activity ->
            activity.openPantryAction(if (shopping) PantryAction.Shopping else PantryAction.Inventory)
            activity.triggerVoiceInput()
            if (changeScreen) activity.openPantryAction(if (shopping) PantryAction.Inventory else PantryAction.Shopping)
        }
    }

    @Test fun allThreeLanguagesReachReviewWithoutLanguageButtons(): Unit = fixture { scenario, eggs, milk ->
        listOf(Triple("Add 2 bottles of milk", "en-US", "Add 2 × milk"),
            Triple("我买了两个鸡蛋", "zh-CN", "Add 2 × eggs"),
            Triple("Tambah dua botol susu", "ms-MY", "Add 2 × milk")).forEach { (text, tag, action) ->
            script(text, tag)
            listen(scenario, false)
            waitFor("Check voice command")
            waitFor(action)
            assertEquals(tag, context.getSharedPreferences("voice_input", Context.MODE_PRIVATE).getString("language", null))
            assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
            assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
            click("Cancel")
        }
    }

    @Test fun originScreenIsPreservedUntilExplicitConfirmation(): Unit = fixture { scenario, eggs, milk ->
        script("我买了两个鸡蛋", "zh-CN")
        listen(scenario, shopping = false, changeScreen = true)
        waitFor("Check voice command")
        waitFor("Add 2 × eggs")
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
        click("Confirm")
        eventually { runBlocking { database.groceryDao().getItemById(eggs)?.quantity == 7 } }
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())

        script("Tambah dua botol susu dan satu sayap ayam", "ms-MY")
        listen(scenario, shopping = true, changeScreen = true)
        waitFor("Check voice command")
        waitFor("Add 2 × milk")
        waitFor("Add 1 × chicken wing")
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
        click("Confirm")
        eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == 2 } }
        assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)

        script("Saya dah guna satu biji telur", "ms-MY")
        listen(scenario, false)
        waitFor("Deduct 1 × eggs")
        assertEquals(7, database.groceryDao().getItemById(eggs)!!.quantity)
        click("Confirm")
        eventually { runBlocking { database.groceryDao().getItemById(eggs)?.quantity == 6 } }
    }

    @Test fun uncertainUnsupportedAndFailedDetectionNeverApplyCommands(): Unit = fixture { scenario, eggs, milk ->
        listOf(Triple(true, "de-DE", 3), Triple(true, "ms-MY", 1)).forEach { (report, tag, confidence) ->
            script("Add 2 bottles of milk", tag, report = report, confidence = confidence)
            listen(scenario, false)
            waitFor("Type Voice Command")
            assertTrue(findTextContaining(VoiceRecognitionFailure.AUTOMATIC_NOT_REPORTED))
            assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
            // Even an unconfirmed language keeps recognized text editable and requires both review and Confirm.
            click("Review command")
            waitFor("Add 2 × milk")
            click("Cancel")
            assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
        }
        script("Tambah dua botol susu", "ms-MY", switchResult = SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL)
        listen(scenario, true)
        waitFor("Type Voice Command")
        assertTrue(findTextContaining(VoiceRecognitionFailure.AUTOMATIC_MODELS_UNAVAILABLE))
        click("Cancel")
        assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
    }

    @Test fun recognizedWordsWithoutLanguageMetadataGoStraightToReviewAndRequireConfirmation(): Unit = fixture { scenario, eggs, milk ->
        val preferences = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
        preferences.edit().putString("language", "zh-CN").commit()
        script(" ", "en-US", report = false)
        listen(scenario, false)
        waitFor("Type Voice Command")
        click("Cancel")
        for (shopping in listOf(false, true)) {
            script("Add 2 bottles of milk", "en-US", report = false)
            listen(scenario, shopping, changeScreen = true)
            waitFor("Check voice command")
            waitFor("Add 2 × milk")
            waitFor(VoiceCommandConfirmationDialog.LANGUAGE_UNCONFIRMED_NOTICE)
            assertEquals("zh-CN", preferences.getString("language", null))
            assertEquals(if (shopping) 4 else 2, database.groceryDao().getItemById(milk)!!.quantity)
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Cancel")
            assertEquals(if (shopping) 4 else 2, database.groceryDao().getItemById(milk)!!.quantity)

            listen(scenario, shopping, changeScreen = true)
            waitFor("Check voice command")
            waitFor("Add 2 × milk")
            if (shopping) {
                scenario.recreate()
                waitFor(VoiceCommandConfirmationDialog.LANGUAGE_UNCONFIRMED_NOTICE)
            }
            click("Confirm")
            if (shopping) {
                eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().singleOrNull()?.quantityNeeded == 2 } }
                assertEquals(4, database.groceryDao().getItemById(milk)!!.quantity)
            } else {
                eventually { runBlocking { database.groceryDao().getItemById(milk)?.quantity == 4 } }
                assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            }
            assertEquals("zh-CN", preferences.getString("language", null))
            assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
        }
    }

    @Test fun cancellingListeningIgnoresLaterServiceResults(): Unit = fixture { scenario, eggs, milk ->
        script("我买了两个鸡蛋", "zh-CN")
        listen(scenario, false)
        scenario.onActivity { activity ->
            val dialog = MainActivity::class.java.getDeclaredField("voiceDialog").apply { isAccessible = true }.get(activity) as BottomSheetDialog
            dialog.dismiss()
        }
        Thread.sleep(1000) // The scripted service delivers after 700 ms unless its cancellation is honored.
        scenario.onActivity { activity ->
            assertNull(activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG))
        }
        assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
        assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
    }

    @Test fun noMatchAndTimeoutCanRetryOnBothScreensWithoutUpdatingBeforeConfirmation(): Unit = fixture { scenario, eggs, milk ->
        for (shopping in listOf(false, true)) {
            script("", "en-US", error = SpeechRecognizer.ERROR_NO_MATCH)
            listen(scenario, shopping, changeScreen = true)
            waitFor("Type Voice Command")
            assertTrue(findTextContaining(VoiceRecognitionFailure.automaticMessage(SpeechRecognizer.ERROR_NO_MATCH)))
            assertEquals(5, database.groceryDao().getItemById(eggs)!!.quantity)
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())

            // A second failure must offer another retry, preserving the original destination.
            script("", "en-US", error = SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            click("Try voice again")
            waitFor("Type Voice Command")
            assertTrue(findTextContaining(VoiceRecognitionFailure.automaticMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)))
            script("Add 2 bottles of milk", "en-US")
            click("Try voice again")
            waitFor("Check voice command")
            waitFor("Add 2 × milk")
            assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            if (shopping) {
                click("Confirm")
                eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().singleOrNull()?.quantityNeeded == 2 } }
                assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
            } else {
                click("Cancel")
                assertEquals(2, database.groceryDao().getItemById(milk)!!.quantity)
            }
        }
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { find(it) }
    }

    private fun findTextContaining(text: String): Boolean {
        fun find(node: AccessibilityNodeInfo): Boolean {
            if (node.text?.toString()?.contains(text) == true) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let { if (find(it)) return true }
            return false
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { find(it) } ?: false
    }

    private fun waitFor(text: String) = eventually { findText(text) != null }
    private fun click(text: String) {
        waitFor(text)
        var node = findText(text)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Voice condition timed out", condition())
    }
}

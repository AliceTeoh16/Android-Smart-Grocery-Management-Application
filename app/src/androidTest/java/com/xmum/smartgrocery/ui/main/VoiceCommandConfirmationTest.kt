package com.xmum.smartgrocery.ui.main

import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.utils.VoiceLanguage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue

/** Runs the real confirmation/dispatch path against RAM only, never the user's database. */
@RunWith(AndroidJUnit4::class)
class VoiceCommandConfirmationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun voiceInventoryAndShoppingPersistFoodAndNonFoodMappings(): Unit = runBlocking {
        val database = testDatabase()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val expected = mapOf("shampoo" to "Personal Care", "plunger" to "Household Essentials",
            "orange juice" to "Beverages", "cream crackers" to "Snacks & Sweets", "frozen chicken" to "Frozen Foods")
        val inventoryCommand = "add 3 shampoo, 3 plunger, 3 orange juice, 3 cream crackers, 3 frozen chicken"
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            review(scenario, inventoryCommand, false)
            eventually {
                var loaded = false
                scenario.onActivity { activity ->
                    val dialog = activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) as VoiceCommandConfirmationDialog
                    loaded = dialog.newItemDrafts?.size == expected.size
                }
                loaded
            }
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) as VoiceCommandConfirmationDialog
                for (draft in dialog.newItemDrafts!!) {
                    assertEquals(expected[draft.name], draft.category)
                    if (draft.category in listOf("Personal Care", "Household Essentials")) {
                        assertNull(draft.expiryDate)
                        assertEquals("1", draft.threshold)
                    }
                }
            }
            assertTrue(database.groceryDao().getAllItemsOnce().isEmpty())
            scenario.recreate()
            waitForText("Check voice command")
            scrollToText("Save & confirm")
            click("Save & confirm")
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
            // Existing-item additions/deductions must preserve the saved category and optional date.
            review(scenario, "add 1 plunger and used 1 shampoo", false)
            click("Confirm")
            eventually { runBlocking { database.groceryDao().getItemByName("plunger")?.quantity == 4 &&
                database.groceryDao().getItemByName("shampoo")?.quantity == 2 } }
            assertEquals("Household Essentials", database.groceryDao().getItemByName("plunger")!!.category)
            assertEquals("Personal Care", database.groceryDao().getItemByName("shampoo")!!.category)
            assertNull(database.groceryDao().getItemByName("shampoo")!!.expiryDate)

            scenario.onActivity { it.openPantryAction(PantryAction.Shopping) }
            review(scenario, "buy 2 shampoo, 2 plunger, 2 orange juice, 2 cream crackers, 2 frozen chicken", true)
            scrollToText("Confirm")
            click("Confirm")
            eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == expected.size } }
            for (item in database.shoppingListDao().getShoppingListItems().first()) {
                assertEquals(expected[item.itemName], item.categorySelection)
                assertEquals(2, item.quantityNeeded)
                if (item.category != "Food & Beverage") assertNull(item.subcategory)
            }
        } finally { scenario.close() }
    }

    @Test fun chineseMultiItemConfirmationAndRememberedLanguage(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val database = testDatabase()
        val eggsId = database.groceryDao().insert(GroceryItem(name = "Eggs", quantity = 5, price = 2.0,
            category = "Other", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val milkId = database.groceryDao().insert(GroceryItem(name = "Milk", quantity = 2, price = 3.0,
            category = "Dairy", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val preferences = context.getSharedPreferences("voice_input", android.content.Context.MODE_PRIVATE)
        val originalLanguage = preferences.getString("language", null)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        fun checkAutomaticSheet(shopping: Boolean) {
            if (android.os.Build.VERSION.SDK_INT < 34) return
            scenario.onActivity { activity ->
                activity.useDeviceVoiceRecognition = true
                activity.openPantryAction(if (shopping) PantryAction.Shopping else PantryAction.Inventory)
                activity.triggerVoiceInput()
                val dialog = MainActivity::class.java.getDeclaredField("voiceDialog").apply { isAccessible = true }
                    .get(activity) as com.google.android.material.bottomsheet.BottomSheetDialog
                val texts = mutableListOf<String>()
                fun inspect(view: android.view.View) {
                    if (view is android.widget.TextView) texts += view.text.toString()
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
                }
                inspect(dialog.window!!.decorView)
                assertTrue(texts.any { it.contains("Auto language detection") })
                assertFalse(texts.any { it == "English" || it == "Chinese" })
                dialog.dismiss()
            }
        }
        try {
            VoiceInputPreferences(context).language = VoiceLanguage.CHINESE
            checkAutomaticSheet(false)
            assertEquals(VoiceLanguage.CHINESE, VoiceInputPreferences(context).language)
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                assertEquals("zh-CN", MainActivity::class.java.getDeclaredField("voiceSessionLanguageTag")
                    .apply { isAccessible = true }.get(activity))
            }
            review(scenario, "添加两个鸡蛋和一瓶牛奶", false)
            waitForText("Add 2 × eggs")
            waitForText("Add 1 × milk")
            assertEquals(5, database.groceryDao().getItemById(eggsId)!!.quantity)
            assertEquals(2, database.groceryDao().getItemById(milkId)!!.quantity)
            scenario.recreate()
            waitForText("Check voice command")
            click("Cancel")
            assertEquals(5, database.groceryDao().getItemById(eggsId)!!.quantity)
            assertEquals(2, database.groceryDao().getItemById(milkId)!!.quantity)
            review(scenario, "用掉两个鸡蛋和一瓶牛奶", false)
            waitForText("Deduct 2 × eggs")
            assertEquals(5, database.groceryDao().getItemById(eggsId)!!.quantity)
            click("Confirm")
            eventually { runBlocking { database.groceryDao().getItemById(eggsId)?.quantity == 3 &&
                database.groceryDao().getItemById(milkId)?.quantity == 1 } }

            review(scenario, "添加两个鸡蛋和", false)
            assertTrue(generateSequence(findText("Confirm")) { it.parent }.any { !it.isEnabled })
            click("Cancel")
            assertEquals(3, database.groceryDao().getItemById(eggsId)!!.quantity)

            scenario.onActivity { it.openPantryAction(PantryAction.Shopping) }
            review(scenario, "买两瓶牛奶和一只鸡翅", true)
            waitForText("Add 2 × milk")
            waitForText("Add 1 × chicken wing")
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Cancel")
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            review(scenario, "买两瓶牛奶和一只鸡翅", true)
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Confirm")
            eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == 2 } }
            val items = database.shoppingListDao().getShoppingListItems().first().associateBy { it.itemName }
            assertEquals(2, items["milk"]!!.quantityNeeded)
            assertEquals(1, items["chicken wing"]!!.quantityNeeded)
            VoiceInputPreferences(context).language = VoiceLanguage.MALAY
            checkAutomaticSheet(true)
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                assertEquals("ms-MY", MainActivity::class.java.getDeclaredField("voiceSessionLanguageTag")
                    .apply { isAccessible = true }.get(activity))
            }
        } finally {
            scenario.close()
            instrumentation.waitForIdleSync()
            preferences.edit().apply { if (originalLanguage == null) remove("language") else putString("language", originalLanguage) }.commit()
        }
    }

    /** Opt-in level-only diagnostic. Raw microphone samples are never stored. */
    @Test fun liveMicrophoneInputLevels() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_microphone_diagnostic") == "true")
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        // Android can silence background recorders. Keep the target app resumed
        // so this diagnostic measures the same capture access as the voice UI.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.waitForIdleSync()
        var meterDialog: android.app.AlertDialog? = null
        scenario.onActivity { activity ->
            meterDialog = android.app.AlertDialog.Builder(activity)
                .setTitle("Microphone signal check")
                .setMessage("Starting… Audio is measured only; no recording is saved.")
                .setCancelable(false).show()
        }
        val minimum = android.media.AudioRecord.getMinBufferSize(16000,
            android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val recorder = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, 16000))
        check(recorder.state == android.media.AudioRecord.STATE_INITIALIZED)
        val samples = ShortArray(4000)
        var totalPeak = 0
        var nonzero = 0L
        try {
            recorder.startRecording()
            instrumentation.sendStatus(2, Bundle().apply { putString("microphone_diagnostic", "Listening for 20 seconds; samples are not saved") })
            repeat(20) { second ->
                val phase = if (second in 5..14) "SPEAK: repeat Add two eggs normally" else "STAY QUIET"
                scenario.onActivity { meterDialog?.setMessage("$phase\n${second + 1} / 20 seconds\nNo audio is saved and no groceries are changed.") }
                var peak = 0
                var squareSum = 0.0
                var signedSum = 0L
                var sampleCount = 0
                var clipped = 0
                var repeated = 0
                var previous: Short? = null
                repeat(4) {
                    val count = recorder.read(samples, 0, samples.size)
                    check(count > 0) { "Audio read failed: $count" }
                    for (index in 0 until count) {
                        val amplitude = kotlin.math.abs(samples[index].toInt())
                        peak = maxOf(peak, amplitude)
                        if (amplitude > 0) nonzero++
                        squareSum += samples[index].toDouble() * samples[index]
                        signedSum += samples[index]
                        if (amplitude >= 32700) clipped++
                        if (previous == samples[index]) repeated++
                        previous = samples[index]
                        sampleCount++
                    }
                }
                totalPeak = maxOf(totalPeak, peak)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("microphone_level", "second=${second + 1}, speaking=${second in 5..14}, peak=${peak / 32768.0}, rms=${kotlin.math.sqrt(squareSum / sampleCount) / 32768.0}, dc=${signedSum.toDouble() / sampleCount / 32768.0}, clipped=${clipped.toDouble() / sampleCount}, repeated=${repeated.toDouble() / sampleCount}")
                })
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("microphone_summary", "peak=${totalPeak / 32768.0}, nonzero_samples=$nonzero")
            })
        } finally {
            recorder.stop()
            recorder.release()
            scenario.onActivity { meterDialog?.dismiss() }
            scenario.close()
        }
    }

    /** Opt-in real recognizer test. The host supplies recorded PCM to the emulator microphone. */
    @Test fun microphoneRecognitionRequiresConfirmation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("voice_audio_injection") == "true")
        val context = instrumentation.targetContext
        val database = testDatabase()
        val eggsId = database.groceryDao().insert(GroceryItem(name = "Eggs", quantity = 5, price = 2.0,
            category = "Other", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun voiceReview(phase: String) {
            eventually {
                var ready = false
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.executePendingTransactions()
                    ready = activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) == null
                }
                ready
            }
            click("Voice command")
            waitForText("Voice Input")
            instrumentation.sendStatus(2, Bundle().apply { putString("audio_phase", phase) })
            waitForText("Check voice command")
        }
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            voiceReview("inventory-add")
            waitForText("Add 2 × eggs")
            assertEquals(5, database.groceryDao().getItemById(eggsId)!!.quantity)
            click("Cancel")
            assertEquals(5, database.groceryDao().getItemById(eggsId)!!.quantity)
            instrumentation.sendStatus(2, Bundle().apply { putString("audio_check", "Inventory add recognized; Cancel left quantity unchanged") })

            voiceReview("inventory-deduct")
            waitForText("Deduct 1 × egg")
            assertEquals(5, database.groceryDao().getItemById(eggsId)!!.quantity)
            click("Confirm")
            eventually { runBlocking { database.groceryDao().getItemById(eggsId)?.quantity == 4 } }
            instrumentation.sendStatus(2, Bundle().apply { putString("audio_check", "Inventory deduction waited for Confirm; test quantity changed 5 to 4") })

            scenario.onActivity { it.openPantryAction(PantryAction.Shopping) }
            voiceReview("shopping-add-cancel")
            waitForText("Add 2 × milk")
            waitForText("Add 1 × chicken wing")
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Cancel")
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            instrumentation.sendStatus(2, Bundle().apply { putString("audio_check", "Shopping voice recognized both items; Cancel left the list unchanged") })

            voiceReview("shopping-add-confirm")
            waitForText("Add 2 × milk")
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Confirm")
            eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == 2 } }
            val items = database.shoppingListDao().getShoppingListItems().first().associateBy { it.itemName }
            assertEquals(2, items["milk"]?.quantityNeeded)
            assertEquals(1, items["chicken wing"]?.quantityNeeded)
            instrumentation.sendStatus(2, Bundle().apply { putString("audio_check", "Shopping items were saved only after Confirm") })
        } finally {
            scenario.close()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun reviewCancelEditConfirmAndRecreation() = runBlocking {
        val context = instrumentation.targetContext
        val database = testDatabase()
        val eggsId = database.groceryDao().insert(GroceryItem(name = "Eggs", quantity = 5, price = 2.0,
            category = "Other", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val milkId = database.groceryDao().insert(GroceryItem(name = "Milk", quantity = 2, price = 3.0,
            category = "Dairy", threshold = 0, expiryDate = System.currentTimeMillis() + 864000000)).toInt()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.openPantryAction(PantryAction.Inventory) }
            review(scenario, "add 2 eggs", false)
            assertEquals(5, database.groceryDao().getItemById(eggsId)?.quantity)
            scenario.recreate()
            waitForText("Check voice command")
            assertEquals(5, database.groceryDao().getItemById(eggsId)?.quantity)
            click("Cancel")
            instrumentation.waitForIdleSync()
            assertEquals(5, database.groceryDao().getItemById(eggsId)?.quantity)

            review(scenario, "I already use 1 egg", false)
            assertEquals(5, database.groceryDao().getItemById(eggsId)?.quantity)
            click("Confirm")
            eventually { runBlocking { database.groceryDao().getItemById(eggsId)?.quantity == 4 } }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertEquals(4, database.groceryDao().getItemById(eggsId)?.quantity)

            review(scenario, "add 2 eggs and used 1 milk", false)
            click("Confirm")
            eventually { runBlocking { database.groceryDao().getItemById(eggsId)?.quantity == 6 &&
                database.groceryDao().getItemById(milkId)?.quantity == 1 } }

            review(scenario, "add 3 dragonfruit", false)
            waitForText("New inventory item")
            assertNull(database.groceryDao().getItemByName("dragonfruit"))
            val editedExpiry = System.currentTimeMillis() + 864000000
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) as VoiceCommandConfirmationDialog
                val draft = dialog.newItemDrafts!!.single()
                dialog.updateNewItemDraft(draft.copy(name = "Red dragonfruit", quantity = "5", price = "12.50",
                    category = "Produce", expiryDate = editedExpiry, threshold = "1"))
            }
            scenario.recreate()
            waitForText("New inventory item")
            scenario.onActivity { activity ->
                val draft = (activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) as VoiceCommandConfirmationDialog)
                    .newItemDrafts!!.single()
                assertEquals("Red dragonfruit", draft.name)
                assertEquals("5", draft.quantity)
                assertEquals("12.50", draft.price)
                assertEquals("Produce", draft.category)
                assertEquals(editedExpiry, draft.expiryDate)
                assertEquals("1", draft.threshold)
            }
            assertNull(database.groceryDao().getItemByName("Red dragonfruit"))
            scrollToText("Save & confirm")
            click("Save & confirm")
            eventually { runBlocking { database.groceryDao().getItemByName("Red dragonfruit") != null } }
            val saved = database.groceryDao().getItemByName("Red dragonfruit")!!
            assertEquals(5, saved.quantity)
            assertEquals(12.50, saved.price, 0.0)
            assertEquals("Food & Beverage", saved.category)
            assertEquals("Produce", saved.subcategory)
            assertEquals(editedExpiry, saved.expiryDate)
            assertEquals(1, saved.threshold)
            scenario.onActivity { activity ->
                assertNull(activity.supportFragmentManager.findFragmentByTag("QuickAddBottomSheet"))
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertEquals(1, database.groceryDao().getAllItemsOnce().count { it.name == "Red dragonfruit" })

            review(scenario, "add 2 papaya and 1 pear", false)
            waitForText("New inventory item")
            assertNull(database.groceryDao().getItemByName("papaya"))
            assertNull(database.groceryDao().getItemByName("pear"))
            scrollToText("Cancel")
            click("Cancel")
            assertNull(database.groceryDao().getItemByName("papaya"))
            assertNull(database.groceryDao().getItemByName("pear"))

            review(scenario, "add 2 papaya and 1 pear", false)
            waitForText("New inventory item")
            scenario.onActivity { activity ->
                val dialog = activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) as VoiceCommandConfirmationDialog
                dialog.newItemDrafts!!.forEach { dialog.updateNewItemDraft(it.copy(threshold = "0")) }
            }
            scrollToText("Save & confirm")
            click("Save & confirm")
            eventually { runBlocking { database.groceryDao().getItemByName("papaya") != null &&
                database.groceryDao().getItemByName("pear") != null } }
            assertEquals(2, database.groceryDao().getItemByName("papaya")!!.quantity)
            assertEquals(1, database.groceryDao().getItemByName("pear")!!.quantity)
            scenario.onActivity { it.openPantryAction(PantryAction.Shopping) }
            review(scenario, "buy 2 bottles of milk, 1 chicken wing", true)
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Cancel")
            instrumentation.waitForIdleSync()
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())

            review(scenario, "buy 2 bottles of milk, 1 chicken wing", true)
            click("Edit command")
            waitForText("Review command")
            click("Review command")
            waitForText("Check voice command")
            scenario.recreate()
            waitForText("Shopping List")
            assertTrue(database.shoppingListDao().getShoppingListItems().first().isEmpty())
            click("Confirm")
            eventually { runBlocking { database.shoppingListDao().getShoppingListItems().first().size == 2 } }
            val items = database.shoppingListDao().getShoppingListItems().first().associateBy { it.itemName }
            assertEquals(2, items["milk"]?.quantityNeeded)
            assertEquals(1, items["chicken wing"]?.quantityNeeded)

            review(scenario, "used 1 milk", true)
            waitForText("Shopping voice commands can add items. Use Inventory to deduct groceries.")
            assertTrue(generateSequence(findText("Confirm")) { it.parent }.any { !it.isEnabled })
            click("Cancel")
            assertEquals(2, database.shoppingListDao().getShoppingListItems().first().size)
            assertEquals(1, database.groceryDao().getItemById(milkId)?.quantity)
        } finally {
            scenario.close()
            instrumentation.waitForIdleSync()
        }
    }

    private fun testDatabase(): AppDatabase {
        val database = (instrumentation as VoiceCommandTestRunner).database
        database.clearAllTables()
        return database
    }

    private fun review(scenario: ActivityScenario<MainActivity>, command: String, shopping: Boolean) {
        eventually {
            var previousReviewClosed = false
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                previousReviewClosed = activity.supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) == null
            }
            previousReviewClosed
        }
        // The same typed fallback used when recognition is unavailable enters the review path.
        scenario.onActivity { activity -> activity.editVoiceCommand(command, shopping) }
        waitForText("Review command")
        click("Review command")
        waitForText("Check voice command")
    }

    private fun findText(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> find(child)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { find(it) }
    }

    private fun click(text: String) {
        waitForText(text)
        var node = findText(text)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Clickable: $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun waitForText(text: String) = eventually { findText(text) != null }

    private fun scrollToText(text: String) {
        repeat(15) {
            if (findText(text) != null) return
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                return false
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let { scroll(it) }
            instrumentation.waitForIdleSync()
            Thread.sleep(150)
        }
        waitForText(text)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Condition timed out", condition())
    }
}

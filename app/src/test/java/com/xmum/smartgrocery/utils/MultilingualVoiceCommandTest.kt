package com.xmum.smartgrocery.utils

import org.junit.Assert.*
import org.junit.Test

class MultilingualVoiceCommandTest {
    private fun review(text: String, shopping: Boolean = false) = VoiceCommandReview.prepare(listOf(text), shopping)

    @Test fun requestedExamplesDoNotNeedLanguageSelection() {
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "milk")), review("Add 2 bottles of milk").commands)
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "eggs")), review("我买了两个鸡蛋").commands)
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "milk")), review("Tambah dua botol susu").commands)
    }

    @Test fun malayMultipleItemsAndRepeatedActions() {
        val result = review("Tolong tambah dua botol susu dan beli tiga biji telur serta satu sayap ayam", true)
        assertTrue(result.toString(), result.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "milk"), VoiceCommand(VoiceAction.ADD, 3, "eggs"),
            VoiceCommand(VoiceAction.ADD, 1, "chicken wing")), result.commands)
        assertEquals(result.commands, VoiceParser.parseAll(result.transcript))
    }

    @Test fun naturalMalayDeductionAndMixedActions() {
        val result = review("Saya dah guna satu biji telur kemudian tambah dua botol susu")
        assertTrue(result.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.DEDUCT, 1, "eggs"), VoiceCommand(VoiceAction.ADD, 2, "milk")), result.commands)
        assertFalse(review(result.transcript, true).canConfirm)
    }

    @Test fun compactMalayClassifiersAreOneItem() {
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 1, "milk"), VoiceCommand(VoiceAction.ADD, 1, "eggs")),
            review("beli sebotol susu dan sebiji telur").commands)
    }

    @Test fun spokenMalayTeensAndTens() {
        listOf("sebelas" to 11, "dua belas" to 12, "sembilan belas" to 19, "dua puluh" to 20,
            "dua puluh satu" to 21, "sembilan puluh sembilan" to 99, "seratus" to 100).forEach { (text, count) ->
            assertEquals(text, VoiceCommand(VoiceAction.ADD, count, "eggs"), review("beli $text biji telur").commands.single())
        }
    }

    @Test fun itemNamesWithMalayCookingWordsStayGroceries() {
        assertEquals(VoiceCommand(VoiceAction.ADD, 2, "cooking oil"), review("tambah dua botol minyak masak").commands.single())
        assertEquals(VoiceAction.RECIPE, review("cadangkan resipi ayam").commands.single().action)
    }

    @Test fun stripsMalayShoppingDestination() {
        assertEquals("milk", review("masukkan dua botol susu ke dalam senarai beli-belah", true).commands.single().itemName)
    }

    @Test fun malayAndEnglishCanShareOneList() {
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "milk"), VoiceCommand(VoiceAction.ADD, 1, "eggs")),
            review("add two bottles of milk dan satu biji telur", true).commands)
    }

    @Test fun rejectsWholeInvalidMalayCommands() {
        listOf("tambah kosong biji telur", "beli sifar botol susu", "tambah negatif dua biji telur", "tambah 1.5 botol susu",
            "beli satu perpuluhan lima botol susu", "tambah setengah botol susu", "beli 999999999999 biji telur",
            "beli dua botol susu dan", "tambah dua biji telur dan tiga", "guna satu biji telur dan tambah").forEach {
            assertFalse(it, review(it).canConfirm)
        }
    }

    @Test fun deductionNeverUpdatesShoppingInAnyLanguage() {
        listOf("used one egg", "用掉一个鸡蛋", "guna satu biji telur").forEach { assertFalse(it, review(it, true).canConfirm) }
    }

    @Test fun compatibilityWithFullwidthRecognizedDigits() {
        assertEquals(VoiceCommand(VoiceAction.ADD, 2, "eggs"), review("我买了２个鸡蛋").commands.single())
    }
}

package com.xmum.smartgrocery.utils

import org.junit.Assert.*
import org.junit.Test

class VoiceRecognitionVocabularyTest {
    @Test fun includesCustomNamesAndBilingualPartsWithoutDuplicates() {
        val words = VoiceRecognitionVocabulary.build(listOf("Cooking Oil (食用油)", " Greek yogurt ", "Greek YOGURT", ""), VoiceLanguage.CHINESE)
        assertTrue(words.containsAll(listOf("Cooking Oil (食用油)", "Cooking Oil", "食用油", "Greek yogurt", "鸡蛋")))
        assertEquals(1, words.count { it.equals("Greek yogurt", ignoreCase = true) })
        assertFalse(words.contains(""))
    }

    @Test fun boundsRequestSizeAndDropsOverlongNames() {
        val words = VoiceRecognitionVocabulary.build(listOf("a".repeat(1000)) + (1..500).map { "Item $it" }, VoiceLanguage.ENGLISH)
        assertEquals(100, words.size)
        assertTrue(words.all { it.length <= 80 })
    }

    @Test fun defaultsSafelyWhenStoredLanguageIsMissingOrUnsupported() {
        assertEquals(VoiceLanguage.CHINESE, VoiceLanguage.fromTag("zh-CN"))
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.fromTag(null))
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.fromTag("unsupported"))
    }

    @Test fun automaticVocabularyIncludesAllLanguagesEvenWithLargeInventories() {
        val words = VoiceRecognitionVocabulary.multilingual((1..500).map { "Custom $it" })
        assertTrue(words.size <= 100)
        assertTrue(words.containsAll(listOf("eggs", "milk", "telur", "susu", "鸡蛋", "牛奶")))
        assertTrue(words.containsAll(listOf("shampoo", "toilet paper", "dishwashing detergent", "frozen dumplings",
            "syampu", "tisu tandas", "pencuci pinggan", "洗发水", "卫生纸", "洗洁精")))
        assertTrue(words.containsAll(listOf("add two eggs", "used one egg", "buy two bottles of milk",
            "我买了两个鸡蛋", "买两瓶牛奶和一个鸡翅", "tambah dua biji telur", "beli dua botol susu")))
    }

    @Test fun acceptsSupportedDetectedLanguageFamiliesWithoutGuessingUnsupportedLanguages() {
        assertEquals(VoiceLanguage.MALAY, VoiceLanguage.detected("ms-MY"))
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.detected("en-GB"))
        assertEquals(VoiceLanguage.CHINESE, VoiceLanguage.detected("zh-TW"))
        assertEquals(VoiceLanguage.CHINESE, VoiceLanguage.detected("cmn-Hans-CN"))
        assertNull(VoiceLanguage.detected("de-DE"))
        assertNull(VoiceLanguage.detected(null))
    }
}

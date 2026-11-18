package com.xmum.smartgrocery.ui.main

import android.content.Context
import android.os.Build
import android.speech.RecognizerIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.utils.VoiceLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceRecognitionSettingsTest {
    @Test fun languagePersistsAndConfiguresTheRecognizer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testPreferences = context.getSharedPreferences("voice_input_test", Context.MODE_PRIVATE)
        testPreferences.edit().clear().commit()
        try {
            assertEquals(VoiceLanguage.ENGLISH, VoiceInputPreferences(testPreferences).language)
            VoiceInputPreferences(testPreferences).language = VoiceLanguage.CHINESE
            val reopened = VoiceInputPreferences(context.getSharedPreferences("voice_input_test", Context.MODE_PRIVATE))
            assertEquals(VoiceLanguage.CHINESE, reopened.language)
            val request = VoiceRecognitionRequest.create(reopened.language, listOf("Greek yogurt", "食用油"))
            assertEquals("zh-CN", request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
            assertFalse(request.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            if (Build.VERSION.SDK_INT >= 34) {
                assertTrue(request.getBooleanExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, false))
                assertEquals(RecognizerIntent.LANGUAGE_SWITCH_BALANCED, request.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
                assertEquals(listOf("en-US", "ms-MY", "zh-CN"), request.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES))
                assertEquals(listOf("en-US", "ms-MY", "zh-CN"), request.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                assertTrue(request.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)!!
                    .containsAll(listOf("Greek yogurt", "食用油", "鸡蛋", "telur", "susu", "milk",
                        "add two eggs", "我买了两个鸡蛋", "tambah dua biji telur")))
            }
            VoiceInputPreferences(testPreferences).language = VoiceLanguage.ENGLISH
            assertEquals("en-US", VoiceRecognitionRequest.create(VoiceInputPreferences(testPreferences).language, emptyList())
                .getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        } finally {
            testPreferences.edit().clear().commit()
        }
    }

    @Test fun platformSupportDoesNotPretendOlderDevicesHaveAutoSwitching() {
        assertFalse(VoiceRecognitionRequest.supportsAutomaticDetection(33))
        assertTrue(VoiceRecognitionRequest.supportsAutomaticDetection(34))
    }
}

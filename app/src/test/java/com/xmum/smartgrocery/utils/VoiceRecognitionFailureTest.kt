package com.xmum.smartgrocery.utils

import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test

class VoiceRecognitionFailureTest {
    @Test fun missingLanguageModelExplainsSetupInsteadOfBlamingSpeech() {
        val message = VoiceRecognitionFailure.message(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, "en-US")
        assertTrue(message.contains("English (US)"))
        assertTrue(message.contains("download or enable"))
        assertTrue(VoiceRecognitionFailure.needsSetup(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertFalse(message.contains("No speech was recognized"))
    }

    @Test fun unsupportedChineseLanguageOffersSetup() {
        assertTrue(VoiceRecognitionFailure.message(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, "zh-CN").contains("Chinese"))
        assertTrue(VoiceRecognitionFailure.needsSetup(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
    }

    @Test fun networkAndMicrophoneFailuresHaveDifferentGuidance() {
        assertTrue(VoiceRecognitionFailure.message(SpeechRecognizer.ERROR_NETWORK, "en-US").contains("internet"))
        assertTrue(VoiceRecognitionFailure.message(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, "en-US").contains("permission"))
        assertFalse(VoiceRecognitionFailure.needsSetup(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test fun automaticModelFailureListsAllRequiredLanguages() {
        val message = VoiceRecognitionFailure.automaticMessage(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertTrue(message.contains("English"))
        assertTrue(message.contains("Malay"))
        assertTrue(message.contains("Mandarin"))
        assertTrue(message.contains("download"))
    }

    @Test fun noMatchAndSilenceOfferRetryWithoutClaimingAModelIsMissing() {
        val noMatch = VoiceRecognitionFailure.automaticMessage(SpeechRecognizer.ERROR_NO_MATCH)
        val timeout = VoiceRecognitionFailure.automaticMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertTrue(noMatch.contains("couldn’t recognize the words"))
        assertTrue(timeout.contains("before speech was detected"))
        listOf(noMatch, timeout).forEach {
            assertTrue(it.contains("Try voice again"))
            assertTrue(it.contains("Listening"))
            assertFalse(it.contains("download"))
        }
    }
}

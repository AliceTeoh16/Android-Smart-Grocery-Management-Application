package com.xmum.smartgrocery.utils

import android.speech.SpeechRecognizer

/** Translate device speech-service failures without treating setup failures as unheard speech. */
object VoiceRecognitionFailure {
    const val AUTOMATIC_PLATFORM_UNAVAILABLE = "Automatic voice language detection needs Android 14 or newer and a compatible speech service. You can type in English, Bahasa Melayu, or Mandarin below."
    const val AUTOMATIC_NOT_REPORTED = "The speech service did not confirm an English, Malay, or Mandarin language. Check the recognized text below, or enable automatic multilingual recognition in Voice settings."
    const val AUTOMATIC_MODELS_UNAVAILABLE = "Automatic language switching needs English, Malay (Malaysia), and Mandarin (China) speech models. Open Voice settings to download or enable them, then try again."

    fun automaticMessage(error: Int): String = if (needsSetup(error)) AUTOMATIC_MODELS_UNAVAILABLE else message(error, "auto")
    fun needsSetup(error: Int): Boolean = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED

    fun message(error: Int, languageTag: String): String {
        val language = when (VoiceLanguage.detected(languageTag)) {
            VoiceLanguage.CHINESE -> "Chinese"
            VoiceLanguage.MALAY -> "Malay (Malaysia)"
            else -> "English (US)"
        }
        return when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "$language speech recognition isn’t ready on this device. Open Voice settings to download or enable this language, then try again."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                "Your device’s speech service doesn’t support $language. Choose another language or check Voice settings."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is required. Allow microphone access for ShelfWise in app settings."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition couldn’t connect. Check your internet connection, then try again."
            SpeechRecognizer.ERROR_AUDIO ->
                "The microphone couldn’t be opened. Check device microphone access and whether another app is using it."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "The speech service is busy. Wait a moment, then try again."
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "The device’s speech service is unavailable. Try again or check your speech service setup."
            SpeechRecognizer.ERROR_NO_MATCH ->
                "The speech service couldn’t recognize the words. Tap Try voice again, wait for Listening…, then say your command. On an emulator, check that its microphone uses your laptop microphone."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "Listening ended before speech was detected. Tap Try voice again and speak as soon as Listening… appears."
            else -> "Speech recognition stopped. Try voice again, or type your command below."
        }
    }
}

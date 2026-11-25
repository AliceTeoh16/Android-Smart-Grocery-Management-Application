package com.xmum.smartgrocery.utils;

import android.speech.SpeechRecognizer;

import androidx.annotation.NonNull;

/** Describes speech service failures and the steps to recover. */
public final class VoiceRecognitionFailure {
    public static final String AUTOMATIC_PLATFORM_UNAVAILABLE =
            "Automatic voice language detection needs Android 14 or newer and a compatible speech service. You can type in English, Bahasa Melayu, or Mandarin below.";
    public static final String AUTOMATIC_NOT_REPORTED =
            "The speech service did not confirm an English, Malay, or Mandarin language. Check the recognized text below, or enable automatic multilingual recognition in Voice settings.";
    public static final String AUTOMATIC_MODELS_UNAVAILABLE =
            "Automatic language switching needs English, Malay (Malaysia), and Mandarin (China) speech models. Open Voice settings to download or enable them, then try again.";

    private VoiceRecognitionFailure() {
    }

    @NonNull
    public static String automaticMessage(int error) {
        return needsSetup(error) ? AUTOMATIC_MODELS_UNAVAILABLE : message(error, "auto");
    }

    public static boolean needsSetup(int error) {
        return error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED;
    }

    @NonNull
    public static String message(int error, @NonNull String languageTag) {
        String language;
        switch (VoiceLanguage.fromTag(languageTag)) {
            case CHINESE:
                language = "Chinese";
                break;
            case MALAY:
                language = "Malay (Malaysia)";
                break;
            default:
                language = "English (US)";
                break;
        }

        switch (error) {
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return language + " speech recognition isn’t ready on this device. Open Voice settings to download or enable this language, then try again.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "Your device’s speech service doesn’t support " + language + ". Choose another language or check Voice settings.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission is required. Allow microphone access for ShelfWise in app settings.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Speech recognition couldn’t connect. Check your internet connection, then try again.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "The microphone couldn’t be opened. Check device microphone access and whether another app is using it.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "The speech service is busy. Wait a moment, then try again.";
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "The device’s speech service is unavailable. Try again or check your speech service setup.";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "The speech service couldn’t recognize the words. Tap Try voice again, wait for Listening…, then say your command. On an emulator, check that its microphone uses your laptop microphone.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Listening ended before speech was detected. Tap Try voice again and speak as soon as Listening… appears.";
            default:
                return "Speech recognition stopped. Try voice again, or type your command below.";
        }
    }
}

package com.xmum.smartgrocery.ui.main

import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import com.xmum.smartgrocery.utils.VoiceLanguage
import com.xmum.smartgrocery.utils.VoiceRecognitionVocabulary

internal object VoiceRecognitionRequest {
    fun supportsAutomaticDetection(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 34

    fun create(language: VoiceLanguage, itemNames: List<String>): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
            // Allow the existing provider's network model when its offline model is unavailable.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    ArrayList(VoiceLanguage.supportedTags))
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    ArrayList(VoiceLanguage.supportedTags))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                    VoiceRecognitionVocabulary.multilingual(itemNames))
            }
            // Use the provider's endpoint defaults; custom silence values can cut off or delay speech.
        }
}

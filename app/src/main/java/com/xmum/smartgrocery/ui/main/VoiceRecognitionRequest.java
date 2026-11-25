package com.xmum.smartgrocery.ui.main;

import android.content.Intent;
import android.os.Build;
import android.speech.RecognizerIntent;

import androidx.annotation.NonNull;

import com.xmum.smartgrocery.utils.VoiceLanguage;
import com.xmum.smartgrocery.utils.VoiceRecognitionVocabulary;

import java.util.ArrayList;
import java.util.List;

final class VoiceRecognitionRequest {
    private VoiceRecognitionRequest() {
    }

    static boolean supportsAutomaticDetection() {
        return supportsAutomaticDetection(Build.VERSION.SDK_INT);
    }

    static boolean supportsAutomaticDetection(int sdk) {
        return sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    @NonNull
    static Intent create(@NonNull VoiceLanguage language, @NonNull List<String> itemNames) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.getTag());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.getTag());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    new ArrayList<>(VoiceLanguage.supportedTags));
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    new ArrayList<>(VoiceLanguage.supportedTags));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                    VoiceRecognitionVocabulary.multilingual(itemNames));
        }
        return intent;
    }
}

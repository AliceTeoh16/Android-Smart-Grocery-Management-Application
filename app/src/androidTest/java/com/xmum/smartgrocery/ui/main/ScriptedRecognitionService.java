package com.xmum.smartgrocery.ui.main;

import android.content.Intent;
import android.content.ContextParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Arrays;

/** Test APK only: real recognizer IPC, no audio, no network, no system speech-setting changes. */
public final class ScriptedRecognitionService extends RecognitionService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int generation;

    @Override protected void onStartListening(Intent intent, Callback callback) {
        try {
            // Attribute this scripted session to its foreground caller without opening a microphone.
            createContext(new ContextParams.Builder()
                .setNextAttributionSource(callback.getCallingAttributionSource()).build());
            if (Build.VERSION.SDK_INT < 34 ||
                !intent.getBooleanExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, false) ||
                !RecognizerIntent.LANGUAGE_SWITCH_BALANCED.equals(intent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH)) ||
                !Arrays.asList("en-US", "ms-MY", "zh-CN").equals(intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))) {
                callback.error(SpeechRecognizer.ERROR_CLIENT);
                return;
            }
            final String text = SpeechScriptProvider.text;
            final Bundle detection = new Bundle();
            detection.putString(SpeechRecognizer.DETECTED_LANGUAGE, SpeechScriptProvider.language);
            detection.putInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, SpeechScriptProvider.confidence);
            detection.putInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, SpeechScriptProvider.switchResult);
            final boolean report = SpeechScriptProvider.reportLanguage;
            final int error = SpeechScriptProvider.error;
            final int token = ++generation;
            callback.readyForSpeech(new Bundle());
            handler.postDelayed(() -> {
                if (token != generation) return;
                try {
                    if (error != 0) {
                        callback.error(error);
                        return;
                    }
                    callback.beginningOfSpeech();
                    if (report) callback.languageDetection(detection);
                    callback.endOfSpeech();
                    Bundle result = new Bundle();
                    result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(Arrays.asList(text)));
                    callback.results(result);
                } catch (RemoteException ignored) { /* Client may have cancelled. */ }
            }, 700);
        } catch (RemoteException ignored) { /* Client may have cancelled. */ }
    }

    @Override protected void onStopListening(Callback callback) { }
    @Override protected void onCancel(Callback callback) {
        generation++;
        handler.removeCallbacksAndMessages(null);
    }
}


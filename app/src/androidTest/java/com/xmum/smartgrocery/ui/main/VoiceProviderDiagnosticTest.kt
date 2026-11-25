package com.xmum.smartgrocery.ui.main

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.utils.VoiceLanguage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in device setup diagnostic: no audio capture, transcripts, or persistent groceries. */
class VoiceProviderDiagnosticTest {
    @Test fun inspectDeviceLanguageModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("inspect_voice_provider") == "true")
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 34)
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun report(message: String) = instrumentation.sendStatus(2, Bundle().apply { putString("voice_provider", message) })
        try {
            for (language in VoiceLanguage.values()) {
                val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
                }
                val complete = CountDownLatch(1)
                var recognizer: SpeechRecognizer? = null
                scenario.onActivity { activity ->
                    recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
                    recognizer!!.checkRecognitionSupport(request, activity.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            report("${language.tag}: installed=${support.installedOnDeviceLanguages}; pending=${support.pendingOnDeviceLanguages}; supported=${support.supportedOnDeviceLanguages}; online=${support.onlineLanguages}")
                            complete.countDown()
                        }
                        override fun onError(error: Int) { report("${language.tag}: support_error=$error"); complete.countDown() }
                    })
                }
                if (!complete.await(12, TimeUnit.SECONDS)) report("${language.tag}: support_timeout")
                if (arguments.getString("download_voice_models") == "true") {
                    val downloaded = CountDownLatch(1)
                    scenario.onActivity { activity ->
                        recognizer!!.triggerModelDownload(request, activity.mainExecutor, object : ModelDownloadListener {
                            override fun onProgress(progress: Int) { report("${language.tag}: download_progress=$progress") }
                            override fun onSuccess() { report("${language.tag}: download_success"); downloaded.countDown() }
                            override fun onScheduled() { report("${language.tag}: download_scheduled"); downloaded.countDown() }
                            override fun onError(error: Int) { report("${language.tag}: download_error=$error"); downloaded.countDown() }
                        })
                    }
                    if (!downloaded.await(30, TimeUnit.SECONDS)) report("${language.tag}: download_pending")
                }
                scenario.onActivity { recognizer?.destroy() }
            }
        } finally { scenario.close() }
    }
}

package com.xmum.smartgrocery.ui.main

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in host-input diagnostic only. PCM is measured in RAM, never saved or sent anywhere. */
class MicrophoneRepeatDiagnosticTest {
    @Test fun compareRepeatedMicrophoneRoutes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("compare_microphone_routes") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var dialog: android.app.AlertDialog? = null
        scenario.onActivity { dialog = android.app.AlertDialog.Builder(it).setTitle("Microphone check")
            .setMessage("Starting…").setCancelable(false).show() }
        val routes = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        try {
            routes.forEachIndexed { index, source ->
                scenario.onActivity { dialog?.setMessage("SPEAK: repeat Add two eggs normally\nRecording ${index + 1} of ${routes.size}\nNo audio is saved or sent. No groceries are changed.") }
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val recorder = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 16000))
                var peak = 0
                var nonzero = 0L
                val samples = ShortArray(4000)
                try {
                    recorder.startRecording()
                    repeat(12) {
                        val count = recorder.read(samples, 0, samples.size)
                        check(count > 0)
                        for (sample in samples.take(count)) {
                            peak = maxOf(peak, kotlin.math.abs(sample.toInt()))
                            if (sample.toInt() != 0) nonzero++
                        }
                    }
                } finally { recorder.stop(); recorder.release() }
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("microphone_route", "recording=${index + 1}, source=$source, peak=${peak / 32768.0}, nonzero=$nonzero")
                })
                Thread.sleep(250)
            }
        } finally { scenario.onActivity { dialog?.dismiss() }; scenario.close() }
    }
}

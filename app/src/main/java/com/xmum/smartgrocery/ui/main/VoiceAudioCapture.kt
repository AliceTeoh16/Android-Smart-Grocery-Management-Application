package com.xmum.smartgrocery.ui.main

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

internal interface VoiceAudioCapture {
    suspend fun record(onReady: () -> Unit, onLevel: (Float, Long) -> Unit): ByteArray
    fun finish()
    fun cancel()
}

/** Bounded, in-memory microphone clip. No temporary file or background recording. */
internal class MicrophoneVoiceCapture : VoiceAudioCapture {
    private val finished = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    override fun finish() { finished.set(true) }
    override fun cancel() { cancelled.set(true); finished.set(true) }

    @SuppressLint("MissingPermission") // The Activity checks RECORD_AUDIO before starting capture.
    override suspend fun record(onReady: () -> Unit, onLevel: (Float, Long) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "This device cannot capture microphone audio." }
        // Record a clip for Gemini using the general microphone path, rather than a device-ASR-tuned path.
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 16000))
        val pcm = ByteArrayOutputStream()
        var peak = 0
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone could not start." }
            withContext(Dispatchers.Main.immediate) { onReady() }
            val samples = ShortArray(1600)
            while (!finished.get() && pcm.size() < VoiceWaveAudio.MAX_PCM_BYTES) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(samples, 0, minOf(samples.size, (VoiceWaveAudio.MAX_PCM_BYTES - pcm.size()) / 2))
                check(count > 0) { "Microphone audio could not be read. Try again." }
                var squares = 0.0
                for (index in 0 until count) {
                    val value = samples[index].toInt()
                    peak = maxOf(peak, abs(value))
                    squares += value.toDouble() * value
                    pcm.write(value and 255)
                    pcm.write((value shr 8) and 255)
                }
                val rms = (sqrt(squares / count) / 32768.0).toFloat()
                val elapsedMs = pcm.size().toLong() * 1000 / 32000
                withContext(Dispatchers.Main.immediate) { onLevel(rms, elapsedMs) }
            }
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw CancellationException("Voice capture cancelled")
            android.util.Log.d("SmartGroceryVoice", "Microphone clip durationMs=${pcm.size().toLong() * 1000 / 32000} peak=$peak source=MIC")
            require(pcm.size() >= VoiceWaveAudio.MIN_PCM_BYTES) { "The recording was too short. Speak, then tap Done speaking." }
            require(peak >= 16) { "No microphone signal was recorded. Try again and check that the green level bar moves while you speak. On an emulator, enable host microphone access." }
            VoiceWaveAudio.encode(pcm.toByteArray())
        } finally {
            try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            finally { recorder.release(); pcm.reset() }
        }
    }
}

internal object VoiceWaveAudio {
    const val MAX_PCM_BYTES = 20 * 16000 * 2
    const val MIN_PCM_BYTES = 12800
    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size in MIN_PCM_BYTES..MAX_PCM_BYTES && pcm.size % 2 == 0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
        }.array()
        return header + pcm
    }
}

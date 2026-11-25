package com.xmum.smartgrocery.ui.main

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal interface VoiceAudioCapture {
    suspend fun record(onReady: () -> Unit, onLevel: (Float, Long) -> Unit): ByteArray
    fun finish()
    fun cancel()
}

/** Dispatches the Java microphone capture and its UI callbacks to their respective threads. */
internal class MicrophoneVoiceCapture : VoiceAudioCapture {
    private val finished = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun finish() { finished.set(true) }
    override fun cancel() { cancelled.set(true); finished.set(true) }

    override suspend fun record(onReady: () -> Unit, onLevel: (Float, Long) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val clip = MicrophoneClip()
            try {
                clip.start()
                withContext(Dispatchers.Main.immediate) { onReady() }
                while (!finished.get() && clip.hasCapacity()) {
                    currentCoroutineContext().ensureActive()
                    val rms = clip.readLevel()
                    val elapsedMillis = clip.elapsedMillis()
                    withContext(Dispatchers.Main.immediate) { onLevel(rms, elapsedMillis) }
                }
                currentCoroutineContext().ensureActive()
                clip.encode(cancelled.get())
            } finally {
                clip.close()
            }
        }
}

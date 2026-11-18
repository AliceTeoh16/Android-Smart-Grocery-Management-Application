package com.xmum.smartgrocery.ui.main

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceWaveAudioTest {
    @Test fun writesValidMono16kWaveWithoutChangingSamples() {
        val pcm = ByteArray(VoiceWaveAudio.MIN_PCM_BYTES) { (it % 127).toByte() }
        val wav = VoiceWaveAudio.encode(pcm)
        val fields = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, fields.getInt(4))
        assertEquals(1, fields.getShort(22).toInt())
        assertEquals(16000, fields.getInt(24))
        assertEquals(16, fields.getShort(34).toInt())
        assertEquals(pcm.size, fields.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
    @Test fun rejectsEmptyPartialSamplesAndOverlongClips() {
        for (size in listOf(0, VoiceWaveAudio.MIN_PCM_BYTES - 2, VoiceWaveAudio.MIN_PCM_BYTES + 1, VoiceWaveAudio.MAX_PCM_BYTES + 2)) {
            try { VoiceWaveAudio.encode(ByteArray(size)); fail("Accepted invalid clip size $size") }
            catch (_: IllegalArgumentException) { }
        }
    }
}

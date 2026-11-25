package com.xmum.smartgrocery.ui.main;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class VoiceWaveAudio {
    static final int MAX_PCM_BYTES = 20 * 16000 * 2;
    static final int MIN_PCM_BYTES = 12800;

    private VoiceWaveAudio() {
    }

    @NonNull
    static byte[] encode(@NonNull byte[] pcm) {
        if (pcm.length < MIN_PCM_BYTES || pcm.length > MAX_PCM_BYTES || pcm.length % 2 != 0) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        byte[] wave = new byte[44 + pcm.length];
        ByteBuffer header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + pcm.length);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(16000);
        header.putInt(32000);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(pcm.length);
        header.put(pcm);
        return wave;
    }
}

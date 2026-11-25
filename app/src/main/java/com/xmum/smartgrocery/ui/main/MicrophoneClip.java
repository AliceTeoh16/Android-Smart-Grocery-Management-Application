package com.xmum.smartgrocery.ui.main;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CancellationException;

/** In-memory PCM clip for the voice transcription request. */
final class MicrophoneClip implements AutoCloseable {
    private static final int SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SECOND = SAMPLE_RATE * 2;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final AudioRecord recorder;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private final short[] samples = new short[1_600];
    private int peak;

    @SuppressLint("MissingPermission") // MainActivity checks RECORD_AUDIO before capture.
    MicrophoneClip() {
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minimum <= 0) {
            throw new IllegalArgumentException("This device cannot capture microphone audio.");
        }
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING,
                Math.max(minimum * 2, SAMPLE_RATE));
    }

    void start() {
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("The microphone could not be opened.");
        }
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("The microphone could not start.");
        }
    }

    boolean hasCapacity() {
        return pcm.size() < VoiceWaveAudio.MAX_PCM_BYTES;
    }

    float readLevel() {
        int count = recorder.read(samples, 0, Math.min(samples.length, (VoiceWaveAudio.MAX_PCM_BYTES - pcm.size()) / 2));
        if (count <= 0) {
            throw new IllegalStateException("Microphone audio could not be read. Try again.");
        }
        double squares = 0;
        for (int index = 0; index < count; index++) {
            int value = samples[index];
            peak = Math.max(peak, Math.abs(value));
            squares += (double) value * value;
            pcm.write(value & 255);
            pcm.write((value >> 8) & 255);
        }
        return (float) (Math.sqrt(squares / count) / 32768.0);
    }

    long elapsedMillis() {
        return (long) pcm.size() * 1000 / BYTES_PER_SECOND;
    }

    @NonNull
    byte[] encode(boolean cancelled) {
        if (cancelled) {
            throw new CancellationException("Voice capture cancelled");
        }
        Log.d("SmartGroceryVoice", "Microphone clip durationMs=" + elapsedMillis() + " peak=" + peak + " source=MIC");
        if (pcm.size() < VoiceWaveAudio.MIN_PCM_BYTES) {
            throw new IllegalArgumentException("The recording was too short. Speak, then tap Done speaking.");
        }
        if (peak < 16) {
            throw new IllegalArgumentException("No microphone signal was recorded. Try again and check that the green level bar moves while you speak. On an emulator, enable host microphone access.");
        }
        return VoiceWaveAudio.encode(pcm.toByteArray());
    }

    @Override
    public void close() {
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } finally {
            recorder.release();
            pcm.reset();
        }
    }
}

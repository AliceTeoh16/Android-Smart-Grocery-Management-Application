package com.xmum.smartgrocery.ui.main;

import android.util.Base64;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.xmum.smartgrocery.BuildConfig;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.VoiceLanguage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.HttpException;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

final class GeminiVoiceTranscription {
    private final Lazy<VoiceTranscriptionApi> api = LazyKt.lazy(() -> new Retrofit.Builder()
            .baseUrl(BuildConfig.AI_BASE_URL)
            .client(new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(VoiceTranscriptionApi.class));

    @NonNull
    Object transcribe(@NonNull byte[] wav, @NonNull List<String> itemNames,
            @NonNull Continuation<? super VoiceTranscription> completion) {
        return AndroidCoroutines.<VoiceAudioRequest, VoiceTranscription>then(
                next -> AndroidCoroutines.onIo(() -> prepare(wav, itemNames), next),
                (request, next) -> AndroidCoroutines.<VoiceAudioResponse, VoiceTranscription>then(
                        response -> AndroidCoroutines.mapFailure(
                                resumed -> api.getValue().transcribe(request, resumed),
                                GeminiVoiceTranscription::mapError, response),
                        (response, unused) -> interpret(response), next),
                completion);
    }

    private static VoiceAudioRequest prepare(byte[] wav, List<String> itemNames) {
        if (wav.length < 44 + VoiceWaveAudio.MIN_PCM_BYTES || wav.length > 44 + VoiceWaveAudio.MAX_PCM_BYTES) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String rawName : itemNames) {
            String name = StringsKt.trim(rawName).toString();
            if (!name.isEmpty() && name.length() <= 80) {
                names.add(name);
                if (names.size() == 40) {
                    break;
                }
            }
        }
        return new VoiceAudioRequest(Base64.encodeToString(wav, Base64.NO_WRAP), "audio/wav", new ArrayList<>(names));
    }

    private static VoiceTranscription interpret(VoiceAudioResponse response) {
        String rawTranscript = response.getTranscript();
        String transcript = rawTranscript == null ? "" : StringsKt.trim(rawTranscript).toString();
        if (!Boolean.TRUE.equals(response.getSpeechDetected()) || transcript.isEmpty()) {
            throw new IllegalArgumentException("No clear speech was recognized. Try again or type your command.");
        }
        if (transcript.length() > 2000) {
            throw new IllegalArgumentException("The voice command is too long. Try a shorter recording.");
        }
        VoiceLanguage language = Boolean.TRUE.equals(response.getLanguageConfident())
                ? VoiceLanguage.detected(response.getLanguage()) : null;
        return new VoiceTranscription(transcript, language);
    }

    private static Throwable mapError(Exception error) {
        if (error instanceof HttpException) {
            String message = readBackendError((HttpException) error);
            return new IOException(message != null ? message.substring(0, Math.min(250, message.length()))
                    : "Voice transcription is unavailable. Try again or type your command.");
        }
        if (error instanceof IOException) {
            return new IOException("Couldn’t reach voice transcription. Check your internet and the ShelfWise backend, "
                    + "then try again or type your command.");
        }
        return error;
    }

    private static String readBackendError(HttpException error) {
        Response<?> response = error.response();
        ResponseBody body = response == null ? null : response.errorBody();
        if (body != null) {
            try {
                BackendError details = new Gson().fromJson(body.string(), BackendError.class);
                return details != null ? details.error : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class BackendError {
        private String error;
    }
}

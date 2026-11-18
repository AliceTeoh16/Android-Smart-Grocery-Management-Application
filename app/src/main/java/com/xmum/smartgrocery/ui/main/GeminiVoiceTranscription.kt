package com.xmum.smartgrocery.ui.main

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.xmum.smartgrocery.BuildConfig
import com.xmum.smartgrocery.utils.VoiceLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class VoiceTranscription(val transcript: String, val language: VoiceLanguage?)
internal data class VoiceAudioRequest(@SerializedName("audio_base64") val audio: String,
    @SerializedName("mime_type") val mimeType: String = "audio/wav",
    @SerializedName("item_names") val names: List<String>)
internal data class VoiceAudioResponse(val transcript: String?, val language: String?,
    @SerializedName("speech_detected") val speechDetected: Boolean?,
    @SerializedName("language_confident") val languageConfident: Boolean?)
internal interface VoiceTranscriptionApi {
    @POST("voice/transcribe") suspend fun transcribe(@Body body: VoiceAudioRequest): VoiceAudioResponse
}

internal class GeminiVoiceTranscription {
    private val api by lazy {
        Retrofit.Builder().baseUrl(BuildConfig.AI_BASE_URL)
            .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(VoiceTranscriptionApi::class.java)
    }

    suspend fun transcribe(wav: ByteArray, itemNames: List<String>): VoiceTranscription {
        val request = withContext(Dispatchers.IO) {
            require(wav.size in 44 + VoiceWaveAudio.MIN_PCM_BYTES..44 + VoiceWaveAudio.MAX_PCM_BYTES)
            VoiceAudioRequest(Base64.encodeToString(wav, Base64.NO_WRAP), names = itemNames
                .map { it.trim() }.filter { it.isNotEmpty() && it.length <= 80 }.distinct().take(40))
        }
        val response = try { api.transcribe(request) }
        catch (error: HttpException) {
            val message = try { Gson().fromJson(error.response()?.errorBody()?.string(), BackendError::class.java)?.error }
                catch (_: Exception) { null }
            throw IOException(message?.take(250) ?: "Voice transcription is unavailable. Try again or type your command.")
        } catch (_: IOException) {
            throw IOException("Couldn’t reach voice transcription. Check your internet and the ShelfWise backend, then try again or type your command.")
        }
        val transcript = response.transcript?.trim().orEmpty()
        require(response.speechDetected == true && transcript.isNotEmpty()) { "No clear speech was recognized. Try again or type your command." }
        require(transcript.length <= 2000) { "The voice command is too long. Try a shorter recording." }
        return VoiceTranscription(transcript, VoiceLanguage.detected(response.language).takeIf { response.languageConfident == true })
    }
    private data class BackendError(val error: String?)
}

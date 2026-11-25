package com.xmum.smartgrocery.ui.main

import com.google.gson.annotations.SerializedName
import com.xmum.smartgrocery.utils.VoiceLanguage
import retrofit2.http.Body
import retrofit2.http.POST

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

package com.xmum.smartgrocery.data.sharing

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemoteUser(val id: String, val email: String, val name: String)
data class RemoteAuth(val user: RemoteUser, val token: String, val expiresAt: Long)
data class RemoteHousehold(val id: String, val name: String, val description: String, val joinCode: String)
data class RemoteMember(val id: String, val email: String, val name: String, val role: String, val status: String, val lastSeenAt: Long?)
data class RemoteRecord(val collection: String, val id: String, val data: JsonObject, val version: Long, val deleted: Boolean, val userId: String)
data class RemoteSnapshot(val household: RemoteHousehold, val members: List<RemoteMember>, val records: List<RemoteRecord>, val acknowledged: List<String>? = null)
data class RemoteHomes(val households: List<RemoteSnapshot>)
data class RemoteMutation(val mutationId: String, val collection: String, val id: String, val baseVersion: Long,
    val deleted: Boolean, val data: JsonObject?, val quantityDelta: Int? = null)

class SharingException(val status: Int, val code: String, message: String) : IOException(message)

class SharingApi(val baseUrl: String) {
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun register(name: String, email: String, password: String) = request("v1/auth/register", null,
        mapOf("name" to name, "email" to email, "password" to password), RemoteAuth::class.java)
    suspend fun login(email: String, password: String) = request("v1/auth/login", null,
        mapOf("email" to email, "password" to password), RemoteAuth::class.java)
    suspend fun homes(token: String) = request("v1/households", token, null, RemoteHomes::class.java)
    suspend fun create(token: String, name: String, description: String) = request("v1/households", token,
        mapOf("name" to name, "description" to description), RemoteSnapshot::class.java)
    suspend fun join(token: String, code: String) = request("v1/households/join", token, mapOf("code" to code), RemoteSnapshot::class.java)
    suspend fun sync(token: String, householdId: String, operations: List<RemoteMutation>) = request("v1/households/$householdId/sync", token,
        mapOf("operations" to operations), RemoteSnapshot::class.java)
    suspend fun invite(token: String, householdId: String, email: String) = request("v1/households/$householdId/invitations", token,
        mapOf("email" to email), RemoteSnapshot::class.java)
    suspend fun leave(token: String, householdId: String) = request("v1/households/$householdId/leave", token, emptyMap<String, String>(), JsonObject::class.java)
    suspend fun logout(token: String) = request("v1/auth/logout", token, emptyMap<String, String>(), JsonObject::class.java)

    private suspend fun <T> request(path: String, token: String?, body: Any?, type: Class<T>): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(baseUrl + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.post(RequestBody.create(MediaType.parse("application/json"), gson.toJson(body)))
        client.newCall(builder.build()).execute().use { response ->
            val content = response.body()?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { gson.fromJson(content, JsonObject::class.java) }.getOrNull()
                throw SharingException(response.code(), error?.get("code")?.asString ?: "SERVER_ERROR",
                    error?.get("message")?.asString ?: "The sharing server could not complete the request.")
            }
            gson.fromJson(content, type) ?: throw IOException("Invalid response from the sharing server.")
        }
    }

    companion object {
        fun normalizeUrl(value: String): String {
            val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("Enter a valid sharing server address.") }
            val host = uri.host.orEmpty().lowercase()
            val parts = host.split('.')
            val address = parts.mapNotNull(String::toIntOrNull)
            val privateAddress = parts.size == 4 && address.size == 4 && parts.all { it == "0" || !it.startsWith('0') } && address.all { it in 0..255 } &&
                (address[0] == 10 || address[0] == 127 || address[0] == 192 && address[1] == 168 || address[0] == 172 && address[1] in 16..31)
            val local = host == "localhost" || privateAddress || host.endsWith(".local") || host.endsWith(".lan")
            require(host.isNotBlank() && uri.port in -1..65535 && uri.port != 0 && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.scheme == "https" || uri.scheme == "http" && local)) {
                "Use HTTPS, or an HTTP address on your local network, without a path or password."
            }
            return value.trim().trimEnd('/') + "/"
        }
    }
}

package com.xmum.smartgrocery.data.sharing

import com.google.gson.JsonObject

data class RemoteUser(val id: String, val email: String, val name: String)
data class RemoteAuth(val user: RemoteUser, val token: String, val expiresAt: Long)
data class RemoteHousehold(val id: String, val name: String, val description: String, val joinCode: String)
data class RemoteMember(val id: String, val email: String, val name: String, val role: String, val status: String, val lastSeenAt: Long?)
data class RemoteRecord(val collection: String, val id: String, val data: JsonObject, val version: Long, val deleted: Boolean, val userId: String)
data class RemoteSnapshot(val household: RemoteHousehold, val members: List<RemoteMember>, val records: List<RemoteRecord>, val acknowledged: List<String>? = null)
data class RemoteHomes(val households: List<RemoteSnapshot>)
data class RemoteMutation(val mutationId: String, val collection: String, val id: String, val baseVersion: Long,
    val deleted: Boolean, val data: JsonObject?, val quantityDelta: Int? = null)

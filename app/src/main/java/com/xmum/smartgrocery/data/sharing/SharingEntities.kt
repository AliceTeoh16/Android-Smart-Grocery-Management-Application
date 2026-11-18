package com.xmum.smartgrocery.data.sharing

import androidx.room.*
import com.xmum.smartgrocery.data.local.entity.UserEntity
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity

@Entity(tableName = "cloud_accounts", primaryKeys = ["localUserId", "serverUrl"], foreignKeys = [
    ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["localUserId"], onDelete = ForeignKey.CASCADE)
])
data class CloudAccount(val localUserId: String, val serverUrl: String, val remoteUserId: String,
    val encryptedToken: String, val expiresAt: Long)

@Entity(tableName = "cloud_users", primaryKeys = ["serverUrl", "remoteUserId"], foreignKeys = [
    ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["localUserId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("localUserId")])
data class CloudUser(val serverUrl: String, val remoteUserId: String, val localUserId: String, val email: String)

@Entity(tableName = "cloud_households", foreignKeys = [
    ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["localHouseholdId"], onDelete = ForeignKey.CASCADE)
], indices = [Index(value = ["serverUrl", "remoteHouseholdId"], unique = true)])
data class CloudHousehold(@PrimaryKey val localHouseholdId: String, val serverUrl: String, val remoteHouseholdId: String)

@Entity(tableName = "sync_entities", primaryKeys = ["householdId", "type", "localKey"],
    indices = [Index(value = ["householdId", "type", "remoteId"], unique = true)])
data class SyncEntity(val householdId: String, val type: String, val localKey: String, val remoteId: String,
    val version: Long = 0, val baseline: String = "", val dirty: Boolean = true, val deleted: Boolean = false, val revision: Long = 1)

@Entity(tableName = "sync_control")
data class SyncControl(@PrimaryKey val id: Int = 1, val applying: Boolean = false, val deviceId: String = java.util.UUID.randomUUID().toString())

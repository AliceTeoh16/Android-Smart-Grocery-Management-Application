package com.xmum.smartgrocery.data.local.entity

import androidx.room.*

/** Local account. Only salted, iterated password hashes are persisted. */
@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
data class UserEntity(
    @PrimaryKey val userId: String,
    val email: String,
    val displayName: String,
    val passwordHash: String,
    val passwordSalt: String,
    val passwordIterations: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "households", indices = [Index(value = ["joinCode"])])
data class HouseholdEntity(@PrimaryKey val householdId: String, val name: String, val joinCode: String,
    @ColumnInfo(defaultValue = "''") val description: String = "")

object HouseholdRole {
    const val OWNER = "Owner"
    const val MEMBER = "Member"
}

object HouseholdMemberStatus {
    const val ACTIVE = "Active"
    const val INVITED = "Invited"
}

@Entity(tableName = "household_members", primaryKeys = ["householdId", "userId"], foreignKeys = [
    ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("userId")])
data class HouseholdMemberEntity(val householdId: String, val userId: String,
    @ColumnInfo(defaultValue = "'Member'") val role: String = HouseholdRole.MEMBER,
    @ColumnInfo(defaultValue = "'Active'") val status: String = HouseholdMemberStatus.ACTIVE,
    val lastSeenAt: Long? = null)

/** The database session is observed by Room, so account and scope changes invalidate data flows. */
@Entity(tableName = "local_session", foreignKeys = [
    ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["householdId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["activeHouseholdId"], onDelete = ForeignKey.SET_NULL)
], indices = [Index("userId"), Index("householdId"), Index("activeHouseholdId")])
data class LocalSessionEntity(@PrimaryKey val id: Int = 1, val userId: String? = null, val householdId: String? = null,
    val activeHouseholdId: String? = householdId)

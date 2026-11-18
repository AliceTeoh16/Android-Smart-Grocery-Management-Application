package com.xmum.smartgrocery.data.local.dao

import androidx.room.*
import com.xmum.smartgrocery.data.local.entity.*
import kotlinx.coroutines.flow.Flow

object HouseholdSql {
    const val ID = "(SELECT COALESCE(activeHouseholdId, householdId) FROM local_session WHERE id = 1)"
    const val ACCESS = "EXISTS (SELECT 1 FROM household_members access WHERE access.householdId = " + ID + " AND access.userId = " + ScopeSql.USER + " AND access.status = 'Active')"
    const val OWNER = "EXISTS (SELECT 1 FROM household_members owner WHERE owner.householdId = " + ID + " AND owner.userId = " + ScopeSql.USER + " AND owner.status = 'Active' AND owner.role = 'Owner')"
}

data class HouseholdMemberProfile(val householdId: String, val userId: String, val displayName: String, val email: String,
    val role: String, val status: String, val lastSeenAt: Long?, val isCurrentUser: Boolean)

@Dao
abstract class HouseholdDao {
    @Query("SELECT * FROM households WHERE householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS)
    abstract fun observeHousehold(): Flow<HouseholdEntity?>

    @Query("SELECT m.householdId, u.userId, u.displayName, COALESCE(c.email,u.email) AS email, m.role, m.status, m.lastSeenAt, u.userId = " + ScopeSql.USER + " AS isCurrentUser FROM household_members m JOIN users u ON u.userId = m.userId LEFT JOIN cloud_users c ON c.localUserId=u.userId AND c.serverUrl=(SELECT serverUrl FROM cloud_households WHERE localHouseholdId=m.householdId) WHERE m.householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS + " ORDER BY CASE m.role WHEN 'Owner' THEN 0 ELSE 1 END, CASE m.status WHEN 'Invited' THEN 1 ELSE 0 END, u.displayName, u.userId")
    abstract fun observeMembers(): Flow<List<HouseholdMemberProfile>>

    @Query("SELECT * FROM household_settings WHERE householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS)
    abstract fun observeSettings(): Flow<HouseholdSettingEntity?>

    @Query("SELECT expiryWarningDays FROM household_settings WHERE householdId = " + ScopeSql.HOUSEHOLD + " AND EXISTS (SELECT 1 FROM household_members m WHERE m.householdId = " + ScopeSql.HOUSEHOLD + " AND m.userId = " + ScopeSql.USER + " AND m.status = 'Active')")
    abstract fun observeScopedWarningDays(): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun initializeSettings(settings: HouseholdSettingEntity)

    @Query("UPDATE household_settings SET expiryWarningDays = MAX(1, MIN(30, expiryWarningDays + :delta)) WHERE householdId = :expectedHouseholdId AND householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.ACCESS)
    abstract suspend fun adjustWarningDays(expectedHouseholdId: String, delta: Int): Int

    @Query("UPDATE household_settings SET expiryWarningDays = :days WHERE householdId = " + ScopeSql.HOUSEHOLD + " AND EXISTS (SELECT 1 FROM household_members m WHERE m.householdId = " + ScopeSql.HOUSEHOLD + " AND m.userId = " + ScopeSql.USER + " AND m.status = 'Active')")
    abstract suspend fun setScopedWarningDays(days: Int): Int

    @Query("UPDATE households SET name = :name, description = :description WHERE householdId = :expectedHouseholdId AND householdId = " + HouseholdSql.ID + " AND " + HouseholdSql.OWNER)
    abstract suspend fun updateDetails(expectedHouseholdId: String, name: String, description: String): Int

    @Query("SELECT * FROM household_members WHERE householdId = :householdId AND userId = :userId")
    abstract suspend fun membership(householdId: String, userId: String): HouseholdMemberEntity?

    @Query("UPDATE household_members SET status = 'Active', lastSeenAt = :now WHERE householdId = :householdId AND userId = :userId")
    abstract suspend fun acceptMembership(householdId: String, userId: String, now: Long)

    @Query("UPDATE household_members SET lastSeenAt = :now WHERE userId = :userId AND status = 'Active'")
    abstract suspend fun touchMember(userId: String, now: Long)
}

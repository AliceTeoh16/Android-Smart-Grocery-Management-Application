package com.xmum.smartgrocery.data.local.dao

import androidx.room.*
import com.xmum.smartgrocery.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?
    @Query("SELECT u.* FROM users u JOIN local_session s ON s.userId = u.userId WHERE s.id = 1")
    suspend fun currentUser(): UserEntity?
    @Insert suspend fun insertUser(user: UserEntity)
    @Insert suspend fun insertHousehold(household: HouseholdEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMember(member: HouseholdMemberEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setSession(session: LocalSessionEntity)
    @Query("SELECT * FROM local_session WHERE id = 1")
    suspend fun session(): LocalSessionEntity?
    @Query("SELECT * FROM local_session WHERE id = 1")
    fun observeSession(): Flow<LocalSessionEntity?>
    @Query("SELECT COALESCE(h.name, 'Personal (private)') AS name, (SELECT COUNT(*) FROM household_members m WHERE m.householdId = s.householdId AND m.status = 'Active') AS memberCount FROM local_session s LEFT JOIN households h ON h.householdId = s.householdId WHERE s.id = 1 AND s.userId IS NOT NULL")
    fun observeContext(): Flow<DataContextInfo?>
    @Query("SELECT h.* FROM households h JOIN household_members m ON m.householdId = h.householdId WHERE m.userId = :userId AND m.status = 'Active' ORDER BY h.name, h.householdId")
    suspend fun householdsFor(userId: String): List<HouseholdEntity>
    @Query("SELECT * FROM households WHERE joinCode = :code LIMIT 1")
    suspend fun householdByCode(code: String): HouseholdEntity?
    @Query("SELECT u.* FROM users u JOIN household_members m ON m.userId = u.userId WHERE m.householdId = :householdId")
    suspend fun members(householdId: String): List<UserEntity>
    @Query("DELETE FROM household_members WHERE householdId = :householdId AND userId = :userId")
    suspend fun deleteMembership(householdId: String, userId: String)
}

data class DataContextInfo(val name: String, val memberCount: Int)

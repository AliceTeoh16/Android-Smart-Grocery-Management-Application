package com.xmum.smartgrocery.data.session

import com.xmum.smartgrocery.data.sharing.SharingRuntime
import androidx.room.withTransaction
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.local.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** Authentication and personal/household context for this device's local database. */
class SessionManager(private val database: AppDatabase) {
    private val dao get() = database.accountDao()
    val sessions get() = dao.observeSession()
    val context get() = dao.observeContext()
    suspend fun currentUserId(): String? = dao.session()?.userId
    suspend fun currentSession(): LocalSessionEntity? = dao.session()
    suspend fun currentUser(): UserEntity? = dao.currentUser()

    /** A reviewed inventory destination and its writes commit together, with the original account. */
    suspend fun <T> withInventoryDestination(target: com.xmum.smartgrocery.data.local.dao.DataScope, action: suspend () -> T): T = database.withTransaction {
        check(currentUserId() == target.userId) { "Account changed. Reopen the inventory form before saving." }
        selectHousehold(target.householdId)
        action()
    }

    suspend fun register(name: String, email: String, password: String): String? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext "Enter your full name."
        if (password.length < 8 || password.none(Char::isDigit)) return@withContext "Use at least 8 characters, with a number."
        val normalized = email.trim().lowercase(Locale.ROOT)
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalized).matches()) return@withContext "Enter a valid email address."
        if (dao.findByEmail(normalized) != null) return@withContext "An account already exists for this email."
        val salt = PasswordHasher.salt()
        val hash = PasswordHasher.hash(password, salt)
        database.withTransaction {
            if (dao.findByEmail(normalized) != null) return@withTransaction "An account already exists for this email."
            val user = UserEntity(UUID.randomUUID().toString(), normalized, name.trim(), hash, salt, PasswordHasher.ITERATIONS)
            dao.insertUser(user)
            dao.setSession(LocalSessionEntity(userId = user.userId))
            null
        }
    }

    suspend fun signIn(email: String, password: String): String? = withContext(Dispatchers.IO) {
        val user = dao.findByEmail(email.trim().lowercase(Locale.ROOT))
        if (user == null)
            return@withContext "No account found with this email. Please sign up to create an account first."
        if (!PasswordHasher.verify(password, user.passwordSalt, user.passwordIterations, user.passwordHash))
            return@withContext "Email or password is incorrect."
        // Always start in the private context when switching accounts.
        database.withTransaction {
            database.householdDao().touchMember(user.userId, System.currentTimeMillis())
            dao.setSession(LocalSessionEntity(userId = user.userId,
                activeHouseholdId = dao.householdsFor(user.userId).firstOrNull()?.householdId))
        }
        null
    }

    suspend fun signOut() = database.withTransaction {
        currentUserId()?.let { database.householdDao().touchMember(it, System.currentTimeMillis()) }
        dao.setSession(LocalSessionEntity())
    }

    /** Target selection and shopping mutation share a transaction with account changes. */
    suspend fun <T> withShoppingScope(assignedTo: String, action: suspend () -> T): T {
        val expectedUser = currentUserId()
        if (assignedTo == "Household" && SharingRuntime.forDatabase(database) != null && households().isEmpty()) createHousehold("Home Pantry")
        return database.withTransaction {
        require(assignedTo == "Me" || assignedTo == "Household") { "Choose Me or Household." }
        val session = checkNotNull(dao.session()) { "Sign in first." }
        check(session.userId == expectedUser) { "Account changed. Review the shopping item again." }
        checkNotNull(session.userId) { "Sign in first." }
        if (assignedTo == "Me") selectHousehold(null)
        else if (session.householdId == null) {
            val home = households().firstOrNull { it.householdId == session.activeHouseholdId }
                ?: households().firstOrNull() ?: if (SharingRuntime.forDatabase(database) == null) createHousehold("Home Pantry") else error("Create or join a household first.")
            selectHousehold(home.householdId)
        }
        action()
    }
    }

    suspend fun households(): List<HouseholdEntity> = currentUserId()?.let { dao.householdsFor(it) }.orEmpty()

    suspend fun selectHousehold(householdId: String?) = database.withTransaction {
        val userId = checkNotNull(currentUserId()) { "Sign in first." }
        require(householdId == null || dao.householdsFor(userId).any { it.householdId == householdId }) { "You are not a member of this household." }
        val active = householdId ?: dao.session()?.activeHouseholdId
            ?: dao.householdsFor(userId).firstOrNull()?.householdId
        dao.setSession(LocalSessionEntity(userId = userId, householdId = householdId, activeHouseholdId = active))
        database.householdDao().touchMember(userId, System.currentTimeMillis())
    }

    suspend fun selectScope(household: Boolean) = database.withTransaction {
        if (!household) selectHousehold(null)
        else {
            val session = checkNotNull(dao.session()) { "Sign in first." }
            val homes = households()
            val active = homes.firstOrNull { it.householdId == session.activeHouseholdId } ?: homes.firstOrNull()
            requireNotNull(active) { "Create or join a household first." }
            selectHousehold(active.householdId)
        }
    }

    suspend fun createHousehold(name: String, description: String = ""): HouseholdEntity {
        SharingRuntime.forDatabase(database)?.let { return it.service.createHousehold(name, description) }
        return database.withTransaction {
        val userId = checkNotNull(currentUserId()) { "Sign in first." }
        require(name.isNotBlank()) { "Enter a household name." }
        var code: String
        do { code = (100000 + SecureRandom().nextInt(900000)).toString() } while (dao.householdByCode(code) != null)
        val household = HouseholdEntity(UUID.randomUUID().toString(), name.trim(), code, description.trim())
        dao.insertHousehold(household)
        dao.insertMember(HouseholdMemberEntity(household.householdId, userId,
            role = HouseholdRole.OWNER, lastSeenAt = System.currentTimeMillis()))
        database.householdDao().initializeSettings(HouseholdSettingEntity(household.householdId))
        dao.setSession(LocalSessionEntity(userId = userId, householdId = household.householdId))
        household
    }
    }

    suspend fun joinHousehold(code: String): HouseholdEntity {
        SharingRuntime.forDatabase(database)?.let { return it.service.joinHousehold(code) }
        return database.withTransaction {
        val userId = checkNotNull(currentUserId()) { "Sign in before joining a household." }
        val household = requireNotNull(dao.householdByCode(code.trim())) { "Household code not found on this device." }
        dao.insertMember(HouseholdMemberEntity(household.householdId, userId))
        database.householdDao().acceptMembership(household.householdId, userId, System.currentTimeMillis())
        database.householdDao().initializeSettings(HouseholdSettingEntity(household.householdId))
        dao.setSession(LocalSessionEntity(userId = userId, householdId = household.householdId))
        household
    }
    }

    suspend fun leaveHousehold(householdId: String) {
        SharingRuntime.forDatabase(database)?.service?.leave(householdId)
        return database.withTransaction {
        val session = checkNotNull(dao.session())
        val userId = checkNotNull(session.userId) { "Sign in first." }
        dao.deleteMembership(householdId, userId)
        val next = dao.householdsFor(userId).firstOrNull()?.householdId
        dao.setSession(session.copy(householdId = session.householdId?.takeIf { it != householdId },
            activeHouseholdId = if (session.activeHouseholdId == householdId) next else session.activeHouseholdId))
    }
    }
}

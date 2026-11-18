package com.xmum.smartgrocery.data.sharing

import androidx.room.withTransaction
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.session.PasswordHasher
import com.xmum.smartgrocery.data.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.Locale

data class SharingStatus(val connected: Boolean = false, val syncing: Boolean = false, val message: String = "Connect your account to share across devices.")

class SharingService(val database: AppDatabase, private val serverUrl: () -> String, private val cipher: TokenCipher = KeystoreTokenCipher()) {
    private val dao get() = database.sharingDao()
    private val engines = mutableMapOf<String, SharingEngine>()
    private val authMutex = Mutex()
    private val operations = Mutex()
    private val _status = MutableStateFlow(SharingStatus())
    val status = _status.asStateFlow()
    private fun api() = SharingApi(SharingApi.normalizeUrl(serverUrl()))
    private fun engine(api: SharingApi) = synchronized(engines) { engines.getOrPut(api.baseUrl) { SharingEngine(database, api, cipher) } }

    suspend fun register(name: String, email: String, password: String): String? = authMutex.withLock {
        try {
            if (database.accountDao().findByEmail(email.trim().lowercase(Locale.ROOT)) != null)
                return@withLock "An account already exists on this device. Log in to connect it."
            val api = api()
            bind(api, api.register(name, email, password), password)
            null
        } catch (error: Exception) { error.message ?: "Unable to create the sharing account." }
    }

    suspend fun login(email: String, password: String): String? = authMutex.withLock {
        val normalized = email.trim().lowercase(Locale.ROOT)
        val local = database.accountDao().findByEmail(normalized)
        try {
            val api = api()
            val auth = try { api.login(normalized, password) }
            catch (error: SharingException) {
                if (error.code != "ACCOUNT_NOT_FOUND" || local == null) throw error
                if (!withContext(Dispatchers.Default) { PasswordHasher.verify(password, local.passwordSalt, local.passwordIterations, local.passwordHash) })
                    return@withLock "Email or password is incorrect."
                // Explicit password-verified upgrade of a pre-existing local account.
                api.register(local.displayName, normalized, password)
            }
            bind(api, auth, password)
            null
        } catch (error: Exception) {
            if (error is IOException && error !is SharingException && local != null &&
                withContext(Dispatchers.Default) { PasswordHasher.verify(password, local.passwordSalt, local.passwordIterations, local.passwordHash) }) {
                database.accountDao().setSession(LocalSessionEntity(userId = local.userId,
                    activeHouseholdId = database.accountDao().householdsFor(local.userId).firstOrNull()?.householdId))
                _status.value = SharingStatus(message = "Sharing server is offline. Personal data and cached household data are available on this device.")
                null
            } else error.message ?: "Cannot reach the sharing server. Check its address and connection."
        }
    }

    private suspend fun bind(api: SharingApi, auth: RemoteAuth, password: String) {
        engine(api).prepare()
        val local = database.accountDao().findByEmail(auth.user.email)
        val mapping = dao.user(api.baseUrl, auth.user.id)
        if (local != null && mapping?.localUserId != local.userId) {
            val prior = dao.userForLocal(local.userId, api.baseUrl)
            require(prior == null || prior.remoteUserId == auth.user.id) { "A different sharing account is linked to this local profile." }
            require(withContext(Dispatchers.Default) { PasswordHasher.verify(password, local.passwordSalt, local.passwordIterations, local.passwordHash) }) {
                "This email belongs to a different local profile. Its private data cannot be claimed by this sharing account."
            }
        }
        val id = local?.userId ?: mapping?.localUserId ?: "account:${SharingEngine.namespace(api.baseUrl)}:${auth.user.id}"
        val salt = PasswordHasher.salt()
        val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(password, salt) }
        database.withTransaction {
            val old = dao.localUser(id)
            val user = UserEntity(id, auth.user.email, auth.user.name, passwordHash, salt, PasswordHasher.ITERATIONS, old?.createdAt ?: System.currentTimeMillis())
            if (old == null) database.accountDao().insertUser(user) else dao.updateUser(user)
            dao.user(CloudUser(api.baseUrl, auth.user.id, id, auth.user.email))
            dao.account(CloudAccount(id, api.baseUrl, auth.user.id, cipher.encrypt(auth.token), auth.expiresAt))
            val session = database.accountDao().session()
            val active = session?.takeIf { it.userId == id }?.activeHouseholdId
                ?: database.accountDao().householdsFor(id).firstOrNull()?.householdId
            database.accountDao().setSession(LocalSessionEntity(userId = id, activeHouseholdId = active))
        }
        _status.value = SharingStatus(true, message = "Connected to household sharing.")
        // Authentication succeeds even if the network drops before the initial data refresh.
        runCatching { synchronize() }
    }

    private suspend fun connection(): Pair<SharingApi, CloudAccount> {
        val api = api()
        val userId = checkNotNull(database.accountDao().session()?.userId) { "Sign in first." }
        val account = checkNotNull(dao.account(userId, api.baseUrl)) { "Connect your account in Share & Connect first." }
        check(account.expiresAt > System.currentTimeMillis()) { "Sign in to the sharing server again." }
        return api to account
    }

    suspend fun synchronize() = operations.withLock { synchronizeUnlocked() }

    private suspend fun synchronizeUnlocked() {
        val current = database.accountDao().session()?.userId
        val api = api()
        val account = current?.let { dao.account(it, api.baseUrl) }
        if (account == null) { _status.value = SharingStatus(); return }
        _status.value = SharingStatus(true, true, "Syncing household changes…")
        try {
            engine(api).prepare()
            engine(api).sync(account)
            _status.value = SharingStatus(true, message = "Household data is up to date.")
        } catch (error: Exception) {
            if (error is SharingException && error.status == 401) {
                dao.forgetAccount(account.localUserId, account.serverUrl)
                _status.value = SharingStatus(message = "Sharing session expired. Connect your account again.")
            } else _status.value = SharingStatus(true, message = if (error is SharingException) error.message.orEmpty()
                else "Sharing is offline. Shared changes are saved on this device and will sync when the server is reachable.")
            throw error
        }
    }

    suspend fun createHousehold(name: String, description: String): HouseholdEntity = operations.withLock {
        val (api, account) = connection()
        val snapshot = api.create(cipher.decrypt(account.encryptedToken), name, description)
        val local = engine(api).importSnapshot(account, snapshot)
        SessionManager(database).selectHousehold(local)
        checkNotNull(dao.localHousehold(local))
    }

    suspend fun publishHousehold(localId: String) = operations.withLock {
        val (api, account) = connection()
        require(dao.household(localId) == null) { "This household is already connected." }
        val member = database.householdDao().membership(localId, account.localUserId)
        require(member?.role == HouseholdRole.OWNER && member.status == HouseholdMemberStatus.ACTIVE) { "Only the owner can enable cross-device sharing." }
        val home = checkNotNull(dao.localHousehold(localId))
        val days = dao.settings(localId)?.expiryWarningDays ?: 3
        val remote = api.create(cipher.decrypt(account.encryptedToken), home.name, home.description)
        engine(api).importSnapshot(account, remote, localId)
        database.withTransaction {
            SharingSchema.bootstrap(database.openHelper.writableDatabase, localId)
            dao.settings(HouseholdSettingEntity(localId, days))
        }
        synchronizeUnlocked()
    }

    suspend fun joinHousehold(code: String): HouseholdEntity = operations.withLock {
        val (api, account) = connection()
        val remote = api.join(cipher.decrypt(account.encryptedToken), code.trim())
        val local = engine(api).importSnapshot(account, remote)
        SessionManager(database).selectHousehold(local)
        return checkNotNull(dao.localHousehold(local))
    }

    suspend fun invite(householdId: String, email: String) = operations.withLock {
        val (api, account) = connection()
        val link = requireNotNull(dao.household(householdId)) { "Enable cross-device sharing for this household first." }
        require(link.serverUrl == api.baseUrl) { "Choose this household's sharing server first." }
        val remote = api.invite(cipher.decrypt(account.encryptedToken), link.remoteHouseholdId, email)
        engine(api).importSnapshot(account, remote)
    }

    suspend fun leave(householdId: String) = operations.withLock {
        val link = dao.household(householdId) ?: return@withLock
        val (api, account) = connection()
        require(link.serverUrl == api.baseUrl) { "Choose this household's sharing server first." }
        synchronizeUnlocked()
        api.leave(cipher.decrypt(account.encryptedToken), link.remoteHouseholdId)
        dao.discardPending(householdId)
    }

    suspend fun logout() = operations.withLock {
        val user = database.accountDao().session()?.userId ?: return@withLock
        val api = api()
        dao.account(user, api.baseUrl)?.let { account ->
            runCatching { api.logout(cipher.decrypt(account.encryptedToken)) }
            dao.forgetAccount(user, api.baseUrl)
        }
        _status.value = SharingStatus()
    }
}

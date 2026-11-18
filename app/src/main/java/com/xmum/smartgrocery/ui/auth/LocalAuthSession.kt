package com.xmum.smartgrocery.ui.auth

import android.content.Context
import android.content.SharedPreferences
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.session.SessionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class LocalAuthProfile(val signedIn: Boolean, val email: String, val displayName: String, val userId: String? = null)

/** UI adapter. Room is authoritative; preferences only cache display information. */
class LocalAuthSession(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(context)
    private val sharing get() = com.xmum.smartgrocery.data.sharing.SharingRuntime.forDatabase(database)?.service
    val manager = SessionManager(database)

    fun currentProfile(): LocalAuthProfile {
        val userId = preferences.getString(USER_ID, null)
        return LocalAuthProfile(
            signedIn = userId != null && preferences.getBoolean(SIGNED_IN, false),
            email = preferences.getString(EMAIL, "").orEmpty(),
            displayName = preferences.getString(DISPLAY_NAME, "").orEmpty(), userId = userId)
    }

    val profiles = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(currentProfile()) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(currentProfile())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    suspend fun signIn(email: String, password: String): String? {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) return "Enter a valid email address."
        val error = sharing?.login(email, password) ?: if (sharing == null) manager.signIn(email, password) else null
        if (error == null) cacheProfile()
        return error
    }

    suspend fun createAccount(name: String, email: String, password: String): String? {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) return "Enter a valid email address."
        val error = sharing?.register(name, email, password) ?: if (sharing == null) manager.register(name, email, password) else null
        if (error == null) cacheProfile()
        return error
    }

    private suspend fun cacheProfile() {
        val user = checkNotNull(manager.currentUser())
        preferences.edit().putBoolean(SIGNED_IN, true).putString(USER_ID, user.userId)
            .putString(EMAIL, user.email).putString(DISPLAY_NAME, user.displayName).apply()
    }

    suspend fun signOut() {
        sharing?.logout()
        manager.signOut()
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "smart_inventory_auth"
        private const val SIGNED_IN = "is_signed_in"
        private const val USER_ID = "user_id"
        private const val EMAIL = "email"
        private const val DISPLAY_NAME = "display_name"
        const val RESET_UNAVAILABLE = "Password reset is unavailable for local accounts. No email was sent."
    }
}

package com.xmum.smartgrocery.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.xmum.smartgrocery.data.local.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Personal preferences remain private; shared inventory reads its household's persisted setting. */
class AppPreferencesRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val expiryWarningDays: StateFlow<Int> = combine(
        database.accountDao().observeSession(), database.householdDao().observeScopedWarningDays(), changes
    ) { session, sharedDays, _ ->
        if (session?.householdId != null) sharedDays ?: DEFAULT_EXPIRY_WARNING_DAYS
        else preferences.getInt(personalKey(session?.userId), preferences.getInt(KEY_EXPIRY_WARNING_DAYS, DEFAULT_EXPIRY_WARNING_DAYS))
    }.stateIn(scope, SharingStarted.Eagerly, DEFAULT_EXPIRY_WARNING_DAYS)

    fun setExpiryWarningDays(days: Int) {
        val safeDays = days.coerceIn(MIN_EXPIRY_WARNING_DAYS, MAX_EXPIRY_WARNING_DAYS)
        scope.launch {
            database.withTransaction {
                val session = database.accountDao().session() ?: return@withTransaction
                val userId = session.userId ?: return@withTransaction
                if (session.householdId != null) database.householdDao().setScopedWarningDays(safeDays)
                else preferences.edit().putInt(personalKey(userId), safeDays).apply()
            }
        }
    }

    private fun personalKey(userId: String?) = "${KEY_EXPIRY_WARNING_DAYS}_${userId ?: "signed_out"}"

    companion object {
        const val DEFAULT_EXPIRY_WARNING_DAYS = 3
        const val MIN_EXPIRY_WARNING_DAYS = 1
        const val MAX_EXPIRY_WARNING_DAYS = 30
        private const val PREFS_NAME = "smart_grocery_preferences"
        private const val KEY_EXPIRY_WARNING_DAYS = "expiry_warning_days"
    }
}

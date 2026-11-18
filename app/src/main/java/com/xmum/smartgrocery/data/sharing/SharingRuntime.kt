package com.xmum.smartgrocery.data.sharing

import android.content.Context
import com.xmum.smartgrocery.BuildConfig
import com.xmum.smartgrocery.data.local.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.WeakHashMap

class SharingRuntime private constructor(context: Context, db: AppDatabase) {
    private val preferences = context.applicationContext.getSharedPreferences("household_sharing", Context.MODE_PRIVATE)
    private val _serverUrl = MutableStateFlow(preferences.getString("server_url", null) ?: BuildConfig.SHARING_BASE_URL)
    val serverUrl = _serverUrl.asStateFlow()
    val service = SharingService(db, { _serverUrl.value })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun configureServer(url: String) {
        val normalized = SharingApi.normalizeUrl(url)
        preferences.edit().putString("server_url", normalized).apply()
        _serverUrl.value = normalized
    }

    @OptIn(FlowPreview::class)
    private fun start(db: AppDatabase) {
        scope.launch {
            combine(db.sharingDao().pending(), db.accountDao().observeSession(), _serverUrl) { _, _, _ -> Unit }
                .debounce(600).collect { runCatching { service.synchronize() } }
        }
        scope.launch { while (isActive) { runCatching { service.synchronize() }; delay(4_000) } }
    }

    companion object {
        /** Instrumented tests explicitly inject their own isolated sync services. */
        var disabledForTests = false
        private val instances = WeakHashMap<AppDatabase, SharingRuntime>()
        @Synchronized fun install(context: Context, database: AppDatabase): SharingRuntime? {
            if (disabledForTests) return null
            return instances.getOrPut(database) { SharingRuntime(context, database).also { it.start(database) } }
        }
        @Synchronized fun forDatabase(database: AppDatabase): SharingRuntime? = instances[database]
        @Synchronized internal fun uninstallForTests(database: AppDatabase) { instances.remove(database)?.scope?.cancel() }
    }
}

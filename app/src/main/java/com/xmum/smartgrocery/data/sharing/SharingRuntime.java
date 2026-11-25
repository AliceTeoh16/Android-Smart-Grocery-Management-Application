package com.xmum.smartgrocery.data.sharing;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.BuildConfig;
import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.utils.AndroidCoroutines;

import java.util.Map;
import java.util.WeakHashMap;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

public final class SharingRuntime {
    private static final String PREFS_NAME = "household_sharing";
    private static final String SERVER_KEY = "server_url";
    private static final Map<AppDatabase, SharingRuntime> INSTANCES = new WeakHashMap<>();

    // Instrumented tests install services for their isolated databases.
    public static volatile boolean disabledForTests;

    private final SharedPreferences preferences;
    private final MutableStateFlow<String> configuredUrl;
    private final StateFlow<String> serverUrl;
    private final SharingService service;
    private final CoroutineScope scope = AndroidCoroutines.ioScope();

    private SharingRuntime(Context context, AppDatabase database) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url = preferences.getString(SERVER_KEY, null);
        // A distributed APK owns its endpoint. Old emulator/custom preferences must not override it.
        if (BuildConfig.MANAGED_SERVER) {
            url = BuildConfig.SHARING_BASE_URL;
            preferences.edit().remove(SERVER_KEY).apply();
        }
        configuredUrl = StateFlowKt.MutableStateFlow(url == null ? BuildConfig.SHARING_BASE_URL : url);
        serverUrl = FlowKt.asStateFlow(configuredUrl);
        service = new SharingService(database, configuredUrl::getValue);
    }

    @NonNull
    public StateFlow<String> getServerUrl() {
        return serverUrl;
    }

    @NonNull
    public SharingService getService() {
        return service;
    }

    public void configureServer(@NonNull String url) {
        String normalized = SharingApi.normalizeUrl(url);
        if (BuildConfig.MANAGED_SERVER && !normalized.equals(BuildConfig.SHARING_BASE_URL)) {
            throw new IllegalArgumentException("This invitation belongs to a different ShelfWise service.");
        }
        preferences.edit().putString(SERVER_KEY, normalized).apply();
        configuredUrl.setValue(normalized);
    }

    private void start(AppDatabase database) {
        Flow<Unit> updates = FlowKt.combine(database.sharingDao().pending(), database.accountDao().observeSession(),
                configuredUrl, (changes, session, url, ignored) -> Unit.INSTANCE);
        AndroidCoroutines.<Unit>collectSilently(scope, FlowKt.debounce(updates, 600L), service::synchronize);
        AndroidCoroutines.<Unit>repeatSilently(scope, 4_000L, service::synchronize);
    }

    @Nullable
    public static synchronized SharingRuntime install(@NonNull Context context, @NonNull AppDatabase database) {
        if (disabledForTests) {
            return null;
        }
        SharingRuntime active = INSTANCES.get(database);
        if (active == null) {
            active = new SharingRuntime(context, database);
            active.start(database);
            INSTANCES.put(database, active);
        }
        return active;
    }

    @Nullable
    public static synchronized SharingRuntime forDatabase(@NonNull AppDatabase database) {
        return INSTANCES.get(database);
    }

    public static synchronized void uninstallForTests(@NonNull AppDatabase database) {
        SharingRuntime active = INSTANCES.remove(database);
        if (active != null) {
            CoroutineScopeKt.cancel(active.scope, null);
        }
    }
}

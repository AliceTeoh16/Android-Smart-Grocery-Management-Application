package com.xmum.smartgrocery.data.repository;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;

import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.utils.AndroidCoroutines;

import java.util.concurrent.atomic.AtomicInteger;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.SharingStarted;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/** Stores private preferences separately from shared household settings. */
public final class AppPreferencesRepository {
    public static final int DEFAULT_EXPIRY_WARNING_DAYS = 3;
    public static final int MIN_EXPIRY_WARNING_DAYS = 1;
    public static final int MAX_EXPIRY_WARNING_DAYS = 30;

    private static final String PREFS_NAME = "smart_grocery_preferences";
    private static final String KEY_EXPIRY_WARNING_DAYS = "expiry_warning_days";

    private final SharedPreferences preferences;
    private final AppDatabase database;
    private final CoroutineScope scope = AndroidCoroutines.ioScope();
    private final AtomicInteger preferenceVersion = new AtomicInteger();
    private final MutableStateFlow<Integer> changes = StateFlowKt.MutableStateFlow(0);
    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sharedPreferences, key) -> changes.setValue(preferenceVersion.incrementAndGet());
    private final StateFlow<Integer> expiryWarningDays;

    public AppPreferencesRepository(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        database = AppDatabase.getDatabase(context);
        preferences.registerOnSharedPreferenceChangeListener(listener);
        expiryWarningDays = FlowKt.stateIn(FlowKt.combine(database.accountDao().observeSession(),
                database.householdDao().observeScopedWarningDays(), changes,
                (session, shared, version, unused) -> readWarningDays(session, shared)),
                scope, SharingStarted.Companion.getEagerly(), DEFAULT_EXPIRY_WARNING_DAYS);
    }

    @NonNull
    public StateFlow<Integer> getExpiryWarningDays() {
        return expiryWarningDays;
    }

    public void setExpiryWarningDays(int days) {
        int safeDays = Math.max(MIN_EXPIRY_WARNING_DAYS, Math.min(MAX_EXPIRY_WARNING_DAYS, days));
        AndroidCoroutines.<Unit>launch(scope, next -> CoroutinesRoom.execute(database, true, () -> {
            database.runInTransaction(() -> saveWarningDays(safeDays));
            return Unit.INSTANCE;
        }, next));
    }

    private int readWarningDays(LocalSessionEntity session, Integer sharedDays) {
        if (session != null && session.getHouseholdId() != null) {
            return sharedDays != null ? sharedDays : DEFAULT_EXPIRY_WARNING_DAYS;
        }
        String userId = session == null ? null : session.getUserId();
        return preferences.getInt(personalKey(userId),
                preferences.getInt(KEY_EXPIRY_WARNING_DAYS, DEFAULT_EXPIRY_WARNING_DAYS));
    }

    private void saveWarningDays(int days) {
        LocalSessionEntity session = database.accountDao().sessionNow();
        if (session == null || session.getUserId() == null) {
            return;
        }
        if (session.getHouseholdId() != null) {
            database.householdDao().setScopedWarningDaysNow(days);
        } else {
            preferences.edit().putInt(personalKey(session.getUserId()), days).apply();
        }
    }

    private static String personalKey(String userId) {
        return KEY_EXPIRY_WARNING_DAYS + "_" + (userId != null ? userId : "signed_out");
    }
}

package com.xmum.smartgrocery.ui.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.entity.UserEntity;
import com.xmum.smartgrocery.data.session.SessionManager;
import com.xmum.smartgrocery.data.sharing.SharingRuntime;
import com.xmum.smartgrocery.data.sharing.SharingService;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.SuspendCall;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.flow.Flow;

/** Room owns the session. Preferences cache the profile shown by the UI. */
public final class LocalAuthSession {
    public static final String RESET_UNAVAILABLE = "Password reset is unavailable for local accounts. No email was sent.";
    private static final String PREFS_NAME = "smart_inventory_auth";
    private static final String SIGNED_IN = "is_signed_in";
    private static final String USER_ID = "user_id";
    private static final String EMAIL = "email";
    private static final String DISPLAY_NAME = "display_name";
    private static final String INVALID_EMAIL = "Enter a valid email address.";

    private final SharedPreferences preferences;
    private final AppDatabase database;
    private final SessionManager manager;
    private final Flow<LocalAuthProfile> profiles;

    public LocalAuthSession(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        database = AppDatabase.getDatabase(context);
        manager = new SessionManager(database);
        profiles = AndroidCoroutines.preferenceSnapshots(preferences, this::currentProfile);
    }

    @NonNull
    public SessionManager getManager() {
        return manager;
    }

    @NonNull
    public Flow<LocalAuthProfile> getProfiles() {
        return profiles;
    }

    @NonNull
    public LocalAuthProfile currentProfile() {
        String userId = preferences.getString(USER_ID, null);
        return new LocalAuthProfile(userId != null && preferences.getBoolean(SIGNED_IN, false),
                orEmpty(preferences.getString(EMAIL, "")), orEmpty(preferences.getString(DISPLAY_NAME, "")), userId);
    }

    @Nullable
    public Object signIn(@NonNull String email, @NonNull String password,
            @NonNull Continuation<? super String> completion) {
        if (!validEmail(email)) {
            return INVALID_EMAIL;
        }
        SharingService sharing = sharingService();
        return authenticate(next -> sharing == null ? manager.signIn(email, password, next) : sharing.login(email, password, next),
                completion);
    }

    @Nullable
    public Object createAccount(@NonNull String name, @NonNull String email, @NonNull String password,
            @NonNull Continuation<? super String> completion) {
        if (!validEmail(email)) {
            return INVALID_EMAIL;
        }
        SharingService sharing = sharingService();
        return authenticate(next -> sharing == null
                ? manager.register(name, email, password, next) : sharing.register(name, email, password, next), completion);
    }

    @NonNull
    public Object signOut(@NonNull Continuation<? super Unit> completion) {
        SharingService sharing = sharingService();
        return AndroidCoroutines.<Unit, Unit>then(next -> sharing == null ? Unit.INSTANCE : sharing.logout(next),
                (remote, next) -> AndroidCoroutines.<Unit, Unit>then(manager::signOut, (local, done) -> {
                    preferences.edit().clear().apply();
                    return Unit.INSTANCE;
                }, next), completion);
    }

    private Object authenticate(SuspendCall<String> request, Continuation<? super String> completion) {
        return AndroidCoroutines.<String, String>then(request, (error, next) -> {
            if (error != null) {
                return error;
            }
            return AndroidCoroutines.<UserEntity, String>then(manager::currentUser, (user, saved) -> {
                if (user == null) {
                    throw new IllegalStateException("Check failed.");
                }
                preferences.edit().putBoolean(SIGNED_IN, true).putString(USER_ID, user.getUserId())
                        .putString(EMAIL, user.getEmail()).putString(DISPLAY_NAME, user.getDisplayName()).apply();
                return null;
            }, next);
        }, completion);
    }

    private SharingService sharingService() {
        SharingRuntime runtime = SharingRuntime.forDatabase(database);
        return runtime == null ? null : runtime.getService();
    }

    private static boolean validEmail(String email) {
        return Patterns.EMAIL_ADDRESS.matcher(StringsKt.trim(email)).matches();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}

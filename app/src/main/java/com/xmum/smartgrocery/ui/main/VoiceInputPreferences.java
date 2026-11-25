package com.xmum.smartgrocery.ui.main;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.xmum.smartgrocery.utils.VoiceLanguage;

/** Remembers the last successfully detected language as a speech recognition hint. */
final class VoiceInputPreferences {
    private final SharedPreferences preferences;

    VoiceInputPreferences(@NonNull Context context) {
        this(context.getApplicationContext().getSharedPreferences("voice_input", Context.MODE_PRIVATE));
    }

    VoiceInputPreferences(@NonNull SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public VoiceLanguage getLanguage() {
        return VoiceLanguage.fromTag(preferences.getString("language", null));
    }

    public void setLanguage(@NonNull VoiceLanguage value) {
        preferences.edit().putString("language", value.getTag()).apply();
    }
}

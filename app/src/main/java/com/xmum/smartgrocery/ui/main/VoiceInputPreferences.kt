package com.xmum.smartgrocery.ui.main

import android.content.Context
import android.content.SharedPreferences
import com.xmum.smartgrocery.utils.VoiceLanguage

/** Last successfully detected language is a bootstrap hint; recognition always enables auto switching. */
internal class VoiceInputPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("voice_input", Context.MODE_PRIVATE))

    var language: VoiceLanguage
        get() = VoiceLanguage.fromTag(preferences.getString("language", null))
        set(value) { preferences.edit().putString("language", value.tag).apply() }
}

package com.xmum.smartgrocery.ui.quickadd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object VoiceInputHandler {

    /**
     * Checks if the app has record audio permission.
     */
    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Array of required permissions for voice input.
     */
    val requiredPermissions = arrayOf(Manifest.permission.RECORD_AUDIO)
}

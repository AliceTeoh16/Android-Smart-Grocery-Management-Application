package com.xmum.smartgrocery.ui.main

import android.app.Application
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import com.xmum.smartgrocery.data.local.AppDatabase

/** Every instrumented voice test uses RAM, including restored Activities and app startup. */
class VoiceCommandTestRunner : AndroidJUnitRunner() {
    lateinit var database: AppDatabase
        private set

    override fun callApplicationOnCreate(app: Application) {
        com.xmum.smartgrocery.data.sharing.SharingRuntime.disabledForTests = true
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, database)
        super.callApplicationOnCreate(app)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::database.isInitialized) database.close()
    }
}

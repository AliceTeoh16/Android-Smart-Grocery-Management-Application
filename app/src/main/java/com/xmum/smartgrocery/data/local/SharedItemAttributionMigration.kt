package com.xmum.smartgrocery.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve rows and pending syncs. Older rows have a known owner but no known latest editor. */
object SharedItemAttributionMigration : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val applying = db.query("SELECT applying FROM sync_control WHERE id = 1").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        db.execSQL("UPDATE sync_control SET applying = 1 WHERE id = 1")
        for (table in listOf("grocery_items", "shopping_list_items")) {
            for (column in listOf("addedByName", "lastModifiedByName", "lastModifiedAction"))
                db.execSQL("ALTER TABLE $table ADD COLUMN $column TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE $table ADD COLUMN lastModifiedAt INTEGER DEFAULT NULL")
            db.execSQL("UPDATE $table SET addedByName = (SELECT displayName FROM users WHERE users.userId = $table.userId) WHERE householdId IS NOT NULL")
        }
        db.execSQL("UPDATE sync_control SET applying = ? WHERE id = 1", arrayOf(applying))
    }
}

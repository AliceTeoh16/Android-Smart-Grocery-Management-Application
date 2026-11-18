package com.xmum.smartgrocery.data.sharing

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SharingSchema {
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE cloud_accounts(localUserId TEXT NOT NULL, serverUrl TEXT NOT NULL, remoteUserId TEXT NOT NULL, encryptedToken TEXT NOT NULL, expiresAt INTEGER NOT NULL, PRIMARY KEY(localUserId,serverUrl), FOREIGN KEY(localUserId) REFERENCES users(userId) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE cloud_users(serverUrl TEXT NOT NULL, remoteUserId TEXT NOT NULL, localUserId TEXT NOT NULL, email TEXT NOT NULL, PRIMARY KEY(serverUrl,remoteUserId), FOREIGN KEY(localUserId) REFERENCES users(userId) ON DELETE RESTRICT)")
            db.execSQL("CREATE INDEX index_cloud_users_localUserId ON cloud_users(localUserId)")
            db.execSQL("CREATE TABLE cloud_households(localHouseholdId TEXT NOT NULL PRIMARY KEY, serverUrl TEXT NOT NULL, remoteHouseholdId TEXT NOT NULL, FOREIGN KEY(localHouseholdId) REFERENCES households(householdId) ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX index_cloud_households_serverUrl_remoteHouseholdId ON cloud_households(serverUrl,remoteHouseholdId)")
            db.execSQL("CREATE TABLE sync_entities(householdId TEXT NOT NULL, type TEXT NOT NULL, localKey TEXT NOT NULL, remoteId TEXT NOT NULL, version INTEGER NOT NULL, baseline TEXT NOT NULL, dirty INTEGER NOT NULL, deleted INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(householdId,type,localKey))")
            db.execSQL("CREATE UNIQUE INDEX index_sync_entities_householdId_type_remoteId ON sync_entities(householdId,type,remoteId)")
            db.execSQL("CREATE TABLE sync_control(id INTEGER NOT NULL PRIMARY KEY, applying INTEGER NOT NULL, deviceId TEXT NOT NULL)")
            db.execSQL("CREATE TABLE sync_outbox(mutationId TEXT NOT NULL PRIMARY KEY, householdId TEXT NOT NULL, markJson TEXT NOT NULL, dataJson TEXT NOT NULL, mutationJson TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_sync_outbox_householdId ON sync_outbox(householdId)")
            db.execSQL("DROP INDEX index_households_joinCode")
            db.execSQL("CREATE INDEX index_households_joinCode ON households(joinCode)")
            install(db)
        }
    }

    private val tables = mapOf("grocery_items" to "inventory", "shopping_list_items" to "shopping", "inventory_logs" to "logs",
        "budget_entries" to "budget", "households" to "household", "household_settings" to "settings")

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO sync_control VALUES(1,0,?)", arrayOf(java.util.UUID.randomUUID().toString()))
        // Room's UPDATE OR ABORT overrides an INSERT OR IGNORE inside a trigger. Guard inserts instead.
        val oldTriggers = db.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='share_grocery_items_update'").use {
            it.moveToFirst() && !it.getString(0).contains("WHERE NOT EXISTS")
        }
        if (oldTriggers) for (table in tables.keys) for (event in listOf("insert", "update", "delete", "move")) {
            db.execSQL("DROP TRIGGER IF EXISTS share_${table}_$event")
        }
        for ((table, type) in tables) {
            fun key(prefix: String) = when (type) { "budget" -> "$prefix.monthYear"; "household", "settings" -> "$prefix.householdId"; else -> "CAST($prefix.id AS TEXT)" }
            fun remote(prefix: String) = when (type) { "budget" -> "$prefix.monthYear"; "household", "settings" -> "'main'"; else -> "lower(hex(randomblob(16)))" }
            fun capture(prefix: String, deleted: Int): String = """
                INSERT INTO sync_entities(householdId,type,localKey,remoteId,version,baseline,dirty,deleted,revision)
                SELECT $prefix.householdId,'$type',${key(prefix)},${remote(prefix)},0,'',1,$deleted,0
                WHERE NOT EXISTS(SELECT 1 FROM sync_entities WHERE householdId=$prefix.householdId AND type='$type' AND localKey=${key(prefix)});
                UPDATE sync_entities SET dirty=1,deleted=$deleted,revision=revision+1
                WHERE householdId=$prefix.householdId AND type='$type' AND localKey=${key(prefix)};
            """.trimIndent()
            val enabled = "(SELECT applying FROM sync_control WHERE id=1)=0"
            val linkedNew = "NEW.householdId IS NOT NULL AND EXISTS(SELECT 1 FROM cloud_households WHERE localHouseholdId=NEW.householdId)"
            val linkedOld = "OLD.householdId IS NOT NULL AND EXISTS(SELECT 1 FROM cloud_households WHERE localHouseholdId=OLD.householdId)"
            db.execSQL("CREATE TRIGGER IF NOT EXISTS share_${table}_insert AFTER INSERT ON $table WHEN $enabled AND $linkedNew BEGIN ${capture("NEW", 0)} END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS share_${table}_update AFTER UPDATE ON $table WHEN $enabled AND $linkedNew BEGIN ${capture("NEW", 0)} END")
            if (type !in listOf("household", "settings", "logs")) {
                db.execSQL("CREATE TRIGGER IF NOT EXISTS share_${table}_delete AFTER DELETE ON $table WHEN $enabled AND $linkedOld BEGIN ${capture("OLD", 1)} END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS share_${table}_move AFTER UPDATE ON $table WHEN $enabled AND $linkedOld AND (NEW.householdId IS NULL OR NEW.householdId!=OLD.householdId) BEGIN ${capture("OLD", 1)} END")
            }
        }
    }

    /** Explicitly published legacy households only; personal rows never enter the outbox. */
    fun bootstrap(db: SupportSQLiteDatabase, householdId: String) {
        for ((table, type) in tables) {
            val key = when (type) { "budget" -> "monthYear"; "household", "settings" -> "householdId"; else -> "CAST(id AS TEXT)" }
            val remote = when (type) { "budget" -> "monthYear"; "household", "settings" -> "'main'"; else -> "lower(hex(randomblob(16)))" }
            db.execSQL("INSERT OR IGNORE INTO sync_entities SELECT householdId, '$type', $key, $remote, 0, '', 1, 0, 1 FROM $table WHERE householdId=?", arrayOf(householdId))
        }
    }
}

package com.xmum.smartgrocery.data.sharing;

import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SharingSchema {
    private static final Map<String, String> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("grocery_items", "inventory");
        TABLES.put("shopping_list_items", "shopping");
        TABLES.put("inventory_logs", "logs");
        TABLES.put("budget_entries", "budget");
        TABLES.put("households", "household");
        TABLES.put("household_settings", "settings");
    }

    public static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE cloud_accounts(localUserId TEXT NOT NULL, serverUrl TEXT NOT NULL, "
                    + "remoteUserId TEXT NOT NULL, encryptedToken TEXT NOT NULL, expiresAt INTEGER NOT NULL, "
                    + "PRIMARY KEY(localUserId,serverUrl), FOREIGN KEY(localUserId) REFERENCES users(userId) ON DELETE CASCADE)");
            db.execSQL("CREATE TABLE cloud_users(serverUrl TEXT NOT NULL, remoteUserId TEXT NOT NULL, "
                    + "localUserId TEXT NOT NULL, email TEXT NOT NULL, PRIMARY KEY(serverUrl,remoteUserId), "
                    + "FOREIGN KEY(localUserId) REFERENCES users(userId) ON DELETE RESTRICT)");
            db.execSQL("CREATE INDEX index_cloud_users_localUserId ON cloud_users(localUserId)");
            db.execSQL("CREATE TABLE cloud_households(localHouseholdId TEXT NOT NULL PRIMARY KEY, "
                    + "serverUrl TEXT NOT NULL, remoteHouseholdId TEXT NOT NULL, "
                    + "FOREIGN KEY(localHouseholdId) REFERENCES households(householdId) ON DELETE CASCADE)");
            db.execSQL("CREATE UNIQUE INDEX index_cloud_households_serverUrl_remoteHouseholdId "
                    + "ON cloud_households(serverUrl,remoteHouseholdId)");
            db.execSQL("CREATE TABLE sync_entities(householdId TEXT NOT NULL, type TEXT NOT NULL, "
                    + "localKey TEXT NOT NULL, remoteId TEXT NOT NULL, version INTEGER NOT NULL, baseline TEXT NOT NULL, "
                    + "dirty INTEGER NOT NULL, deleted INTEGER NOT NULL, revision INTEGER NOT NULL, "
                    + "PRIMARY KEY(householdId,type,localKey))");
            db.execSQL("CREATE UNIQUE INDEX index_sync_entities_householdId_type_remoteId "
                    + "ON sync_entities(householdId,type,remoteId)");
            db.execSQL("CREATE TABLE sync_control(id INTEGER NOT NULL PRIMARY KEY, applying INTEGER NOT NULL, deviceId TEXT NOT NULL)");
            db.execSQL("CREATE TABLE sync_outbox(mutationId TEXT NOT NULL PRIMARY KEY, householdId TEXT NOT NULL, "
                    + "markJson TEXT NOT NULL, dataJson TEXT NOT NULL, mutationJson TEXT NOT NULL)");
            db.execSQL("CREATE INDEX index_sync_outbox_householdId ON sync_outbox(householdId)");
            db.execSQL("DROP INDEX index_households_joinCode");
            db.execSQL("CREATE INDEX index_households_joinCode ON households(joinCode)");
            install(db);
        }
    };

    private SharingSchema() {
    }

    public static void install(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO sync_control VALUES(1,0,?)",
                new Object[]{UUID.randomUUID().toString()});
        // Room's UPDATE OR ABORT overrides an INSERT OR IGNORE inside a trigger.
        boolean oldTriggers;
        try (Cursor cursor = db.query("SELECT sql FROM sqlite_master WHERE type='trigger' "
                + "AND name='share_grocery_items_update'")) {
            oldTriggers = cursor.moveToFirst() && !cursor.getString(0).contains("WHERE NOT EXISTS");
        }
        if (oldTriggers) {
            for (String table : TABLES.keySet()) {
                for (String event : new String[]{"insert", "update", "delete", "move"}) {
                    db.execSQL("DROP TRIGGER IF EXISTS share_" + table + "_" + event);
                }
            }
        }

        String enabled = "(SELECT applying FROM sync_control WHERE id=1)=0";
        String linkedNew = "NEW.householdId IS NOT NULL AND EXISTS"
                + "(SELECT 1 FROM cloud_households WHERE localHouseholdId=NEW.householdId)";
        String linkedOld = "OLD.householdId IS NOT NULL AND EXISTS"
                + "(SELECT 1 FROM cloud_households WHERE localHouseholdId=OLD.householdId)";
        for (Map.Entry<String, String> entry : TABLES.entrySet()) {
            String table = entry.getKey();
            String type = entry.getValue();
            String createTrigger = "CREATE TRIGGER IF NOT EXISTS share_" + table;
            String captureNew = capture(type, "NEW.", 0);
            db.execSQL(createTrigger + "_insert AFTER INSERT ON " + table + " WHEN " + enabled
                    + " AND " + linkedNew + " BEGIN " + captureNew + " END");
            db.execSQL(createTrigger + "_update AFTER UPDATE ON " + table + " WHEN " + enabled
                    + " AND " + linkedNew + " BEGIN " + captureNew + " END");
            if (!"household".equals(type) && !"settings".equals(type) && !"logs".equals(type)) {
                String captureOld = capture(type, "OLD.", 1);
                db.execSQL(createTrigger + "_delete AFTER DELETE ON " + table + " WHEN " + enabled
                        + " AND " + linkedOld + " BEGIN " + captureOld + " END");
                db.execSQL(createTrigger + "_move AFTER UPDATE ON " + table + " WHEN " + enabled
                        + " AND " + linkedOld + " AND (NEW.householdId IS NULL OR NEW.householdId!=OLD.householdId)"
                        + " BEGIN " + captureOld + " END");
            }
        }
    }

    /** Queues rows belonging to a household when that household is published. */
    public static void bootstrap(@NonNull SupportSQLiteDatabase db, @NonNull String householdId) {
        for (Map.Entry<String, String> entry : TABLES.entrySet()) {
            String type = entry.getValue();
            db.execSQL("INSERT OR IGNORE INTO sync_entities SELECT householdId, '" + type + "', "
                    + localKey(type, "") + ", " + remoteKey(type, "") + ", 0, '', 1, 0, 1 FROM "
                    + entry.getKey() + " WHERE householdId=?", new Object[]{householdId});
        }
    }

    private static String capture(String type, String prefix, int deleted) {
        String key = localKey(type, prefix);
        String match = "householdId=" + prefix + "householdId AND type='" + type + "' AND localKey=" + key;
        return "INSERT INTO sync_entities(householdId,type,localKey,remoteId,version,baseline,dirty,deleted,revision) "
                + "SELECT " + prefix + "householdId,'" + type + "'," + key + "," + remoteKey(type, prefix)
                + ",0,'',1," + deleted + ",0 WHERE NOT EXISTS(SELECT 1 FROM sync_entities WHERE " + match + "); "
                + "UPDATE sync_entities SET dirty=1,deleted=" + deleted + ",revision=revision+1 WHERE " + match + ";";
    }

    private static String localKey(String type, String prefix) {
        switch (type) {
            case "budget":
                return prefix + "monthYear";
            case "household":
            case "settings":
                return prefix + "householdId";
            default:
                return "CAST(" + prefix + "id AS TEXT)";
        }
    }

    private static String remoteKey(String type, String prefix) {
        switch (type) {
            case "budget":
                return prefix + "monthYear";
            case "household":
            case "settings":
                return "'main'";
            default:
                return "lower(hex(randomblob(16)))";
        }
    }
}

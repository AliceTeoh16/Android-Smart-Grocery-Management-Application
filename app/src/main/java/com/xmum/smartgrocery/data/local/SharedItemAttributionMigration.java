package com.xmum.smartgrocery.data.local;

import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Preserves rows and pending syncs while adding shared item attribution. */
public final class SharedItemAttributionMigration extends Migration {
    public static final SharedItemAttributionMigration INSTANCE = new SharedItemAttributionMigration();

    private SharedItemAttributionMigration() {
        super(12, 13);
    }

    @Override
    public void migrate(@NonNull SupportSQLiteDatabase db) {
        int applying;
        try (Cursor cursor = db.query("SELECT applying FROM sync_control WHERE id = 1")) {
            applying = cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
        db.execSQL("UPDATE sync_control SET applying = 1 WHERE id = 1");
        for (String table : new String[]{"grocery_items", "shopping_list_items"}) {
            for (String column : new String[]{"addedByName", "lastModifiedByName", "lastModifiedAction"}) {
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT DEFAULT NULL");
            }
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN lastModifiedAt INTEGER DEFAULT NULL");
            db.execSQL("UPDATE " + table + " SET addedByName = "
                    + "(SELECT displayName FROM users WHERE users.userId = " + table + ".userId) "
                    + "WHERE householdId IS NOT NULL");
        }
        db.execSQL("UPDATE sync_control SET applying = ? WHERE id = 1", new Object[]{applying});
    }
}

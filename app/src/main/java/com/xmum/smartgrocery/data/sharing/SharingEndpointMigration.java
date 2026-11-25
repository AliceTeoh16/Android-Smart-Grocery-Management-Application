package com.xmum.smartgrocery.data.sharing;

import android.database.Cursor;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.xmum.smartgrocery.data.local.AppDatabase;
import java.util.ArrayList;
import java.util.List;

/** Called only after password verification and successful remote authentication, inside a transaction. */
final class SharingEndpointMigration {
    private SharingEndpointMigration() {}

    static void relocate(AppDatabase database, String localUserId, String remoteUserId, String server) {
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        List<String> aliases = new ArrayList<>();
        try (Cursor cursor = sql.query("SELECT serverUrl FROM cloud_users WHERE localUserId=? "
                + "AND remoteUserId=? AND serverUrl<>?", new Object[]{localUserId, remoteUserId, server})) {
            while (cursor.moveToNext()) aliases.add(cursor.getString(0));
        }
        for (String alias : aliases) {
            try (Cursor conflicts = sql.query("SELECT 1 FROM cloud_users old JOIN cloud_users target "
                    + "ON target.remoteUserId=old.remoteUserId AND target.serverUrl=? "
                    + "WHERE old.serverUrl=? AND target.localUserId<>old.localUserId LIMIT 1", new Object[]{server, alias})) {
                if (conflicts.moveToFirst()) throw new IllegalStateException("Unable to reconnect this cached account safely.");
            }
            try (Cursor conflicts = sql.query("SELECT 1 FROM cloud_accounts old JOIN cloud_accounts target "
                    + "ON target.localUserId=old.localUserId AND target.serverUrl=? "
                    + "WHERE old.serverUrl=? AND target.remoteUserId<>old.remoteUserId LIMIT 1", new Object[]{server, alias})) {
                if (conflicts.moveToFirst()) throw new IllegalStateException("Unable to reconnect this cached account safely.");
            }
            // Conflicts roll the transaction back instead of discarding cached household data.
            sql.execSQL("UPDATE cloud_households SET serverUrl=? WHERE serverUrl=?", new Object[]{server, alias});
            sql.execSQL("INSERT OR IGNORE INTO cloud_users(serverUrl,remoteUserId,localUserId,email) "
                    + "SELECT ?,remoteUserId,localUserId,email FROM cloud_users WHERE serverUrl=?", new Object[]{server, alias});
            sql.execSQL("INSERT OR IGNORE INTO cloud_accounts(localUserId,serverUrl,remoteUserId,encryptedToken,expiresAt) "
                    + "SELECT localUserId,?,remoteUserId,encryptedToken,expiresAt FROM cloud_accounts WHERE serverUrl=?", new Object[]{server, alias});
            sql.execSQL("DELETE FROM cloud_users WHERE serverUrl=?", new Object[]{alias});
            sql.execSQL("DELETE FROM cloud_accounts WHERE serverUrl=?", new Object[]{alias});
        }
    }
}

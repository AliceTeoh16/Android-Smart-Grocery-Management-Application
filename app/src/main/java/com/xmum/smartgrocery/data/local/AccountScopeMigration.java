package com.xmum.smartgrocery.data.local;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Preserves device data under a disabled legacy owner to keep it separate from new accounts. */
final class AccountScopeMigration extends Migration {
    static final String LEGACY_USER_ID = "legacy-unclaimed";
    static final AccountScopeMigration INSTANCE = new AccountScopeMigration();

    private AccountScopeMigration() {
        super(9, 10);
    }

    @Override
    public void migrate(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE users (userId TEXT NOT NULL PRIMARY KEY, email TEXT NOT NULL, "
                + "displayName TEXT NOT NULL, passwordHash TEXT NOT NULL, passwordSalt TEXT NOT NULL, "
                + "passwordIterations INTEGER NOT NULL, createdAt INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX index_users_email ON users(email)");
        db.execSQL("INSERT INTO users VALUES ('legacy-unclaimed', 'legacy-unclaimed@local.invalid', 'Unclaimed legacy data', '', '', 600000, 0)");
        db.execSQL("CREATE TABLE households (householdId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, joinCode TEXT NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX index_households_joinCode ON households(joinCode)");
        db.execSQL("CREATE TABLE household_members (householdId TEXT NOT NULL, userId TEXT NOT NULL, "
                + "PRIMARY KEY(householdId, userId), "
                + "FOREIGN KEY(householdId) REFERENCES households(householdId) ON DELETE CASCADE, "
                + "FOREIGN KEY(userId) REFERENCES users(userId) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX index_household_members_userId ON household_members(userId)");
        db.execSQL("CREATE TABLE local_session (id INTEGER NOT NULL PRIMARY KEY, userId TEXT, householdId TEXT, "
                + "FOREIGN KEY(userId) REFERENCES users(userId) ON DELETE CASCADE, "
                + "FOREIGN KEY(householdId) REFERENCES households(householdId) ON DELETE SET NULL)");
        db.execSQL("CREATE INDEX index_local_session_userId ON local_session(userId)");
        db.execSQL("CREATE INDEX index_local_session_householdId ON local_session(householdId)");

        // Copy logs before rebuilding their parent so ON DELETE SET NULL cannot erase their links.
        db.execSQL("CREATE TEMP TABLE legacy_inventory_logs AS SELECT * FROM inventory_logs");
        db.execSQL("DROP TABLE inventory_logs");
        String owner = ", userId TEXT NOT NULL, householdId TEXT, FOREIGN KEY(userId) REFERENCES users(userId) ON DELETE RESTRICT, FOREIGN KEY(householdId) REFERENCES households(householdId) ON DELETE RESTRICT";
        rebuild(db, "grocery_items", "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, "
                + "quantity INTEGER NOT NULL, price REAL NOT NULL, category TEXT NOT NULL, expiryDate INTEGER, "
                + "threshold INTEGER NOT NULL, purchasedDate INTEGER NOT NULL, subcategory TEXT" + owner,
                "id, name, quantity, price, category, expiryDate, threshold, purchasedDate, subcategory", null, null, false);
        rebuild(db, "shopping_list_items", "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceItemId INTEGER, "
                + "itemName TEXT NOT NULL, category TEXT NOT NULL, quantityNeeded INTEGER NOT NULL, "
                + "quantityLabel TEXT NOT NULL DEFAULT '', estimatedUnitPrice REAL NOT NULL, "
                + "addedBy TEXT NOT NULL DEFAULT 'Auto', assignedTo TEXT NOT NULL DEFAULT 'Household', "
                + "isChecked INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, subcategory TEXT" + owner,
                "id, sourceItemId, itemName, category, quantityNeeded, quantityLabel, estimatedUnitPrice, addedBy, assignedTo, isChecked, createdAt, updatedAt, subcategory", null, null, false);
        db.execSQL("CREATE UNIQUE INDEX index_shopping_list_items_sourceItemId ON shopping_list_items(sourceItemId)");
        rebuild(db, "budget_entries", "monthYear TEXT NOT NULL, limitAmount REAL NOT NULL, scopeKey TEXT NOT NULL" + owner + ", PRIMARY KEY(scopeKey, monthYear)",
                "monthYear, limitAmount", "scopeKey", "'u:legacy-unclaimed'", false);
        rebuild(db, "checklist_history", "id TEXT NOT NULL PRIMARY KEY, dishName TEXT NOT NULL, servings INTEGER NOT NULL, "
                + "status TEXT NOT NULL, created_at INTEGER NOT NULL, missingChecklistJson TEXT NOT NULL, "
                + "inventoryDeductedJson TEXT NOT NULL, addedShoppingKeysJson TEXT NOT NULL, stateJson TEXT NOT NULL DEFAULT '', "
                + "userId TEXT NOT NULL, FOREIGN KEY(userId) REFERENCES users(userId) ON DELETE RESTRICT",
                "id, dishName, servings, status, created_at, missingChecklistJson, inventoryDeductedJson, addedShoppingKeysJson, stateJson", null, null, true);
        db.execSQL("CREATE TABLE inventory_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER, "
                + "itemName TEXT NOT NULL, category TEXT NOT NULL, changeType TEXT NOT NULL, amountChanged INTEGER NOT NULL, "
                + "unitPriceAtChange REAL NOT NULL, timestamp INTEGER NOT NULL" + owner + ", FOREIGN KEY(itemId) REFERENCES grocery_items(id) ON DELETE SET NULL)");
        db.execSQL("INSERT INTO inventory_logs SELECT id, itemId, itemName, category, changeType, amountChanged, "
                + "unitPriceAtChange, timestamp, 'legacy-unclaimed', NULL FROM legacy_inventory_logs");
        db.execSQL("DROP TABLE legacy_inventory_logs");
        for (String column : new String[]{"userId", "householdId", "itemId", "timestamp", "changeType"}) {
            db.execSQL("CREATE INDEX index_inventory_logs_" + column + " ON inventory_logs(" + column + ")");
        }
    }

    private static void rebuild(SupportSQLiteDatabase db, String table, String definition, String columns,
            String extraColumn, String extraValue, boolean personal) {
        String scopedTable = table + "_scoped";
        db.execSQL("CREATE TABLE " + scopedTable + " (" + definition + ")");
        String target = columns + (extraColumn != null ? ", " + extraColumn : "")
                + ", userId" + (personal ? "" : ", householdId");
        String source = columns + (extraValue != null ? ", " + extraValue : "")
                + ", 'legacy-unclaimed'" + (personal ? "" : ", NULL");
        db.execSQL("INSERT INTO " + scopedTable + " (" + target + ") SELECT " + source + " FROM " + table);
        db.execSQL("DROP TABLE " + table);
        db.execSQL("ALTER TABLE " + scopedTable + " RENAME TO " + table);
        db.execSQL("CREATE INDEX index_" + table + "_userId ON " + table + "(userId)");
        if (!personal) {
            db.execSQL("CREATE INDEX index_" + table + "_householdId ON " + table + "(householdId)");
        }
    }
}

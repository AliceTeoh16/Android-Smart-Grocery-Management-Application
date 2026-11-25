package com.xmum.smartgrocery.data.local;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.xmum.smartgrocery.data.local.dao.AccountDao;
import com.xmum.smartgrocery.data.local.dao.BudgetDao;
import com.xmum.smartgrocery.data.local.dao.ChecklistHistoryDao;
import com.xmum.smartgrocery.data.local.dao.GroceryDao;
import com.xmum.smartgrocery.data.local.dao.HouseholdDao;
import com.xmum.smartgrocery.data.local.dao.ShoppingListDao;
import com.xmum.smartgrocery.data.local.entity.BudgetEntry;
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberEntity;
import com.xmum.smartgrocery.data.local.entity.HouseholdSettingEntity;
import com.xmum.smartgrocery.data.local.entity.InventoryLog;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;
import com.xmum.smartgrocery.data.local.entity.UserEntity;
import com.xmum.smartgrocery.data.sharing.CloudAccount;
import com.xmum.smartgrocery.data.sharing.CloudHousehold;
import com.xmum.smartgrocery.data.sharing.CloudUser;
import com.xmum.smartgrocery.data.sharing.PendingOperation;
import com.xmum.smartgrocery.data.sharing.SharingDao;
import com.xmum.smartgrocery.data.sharing.SharingSchema;
import com.xmum.smartgrocery.data.sharing.SyncControl;
import com.xmum.smartgrocery.data.sharing.SyncEntity;

@Database(
        entities = {
                GroceryItem.class, BudgetEntry.class, InventoryLog.class, ShoppingListItem.class,
                ChecklistHistoryItem.class, UserEntity.class, HouseholdEntity.class, HouseholdMemberEntity.class,
                LocalSessionEntity.class, HouseholdSettingEntity.class, CloudAccount.class, CloudUser.class,
                CloudHousehold.class, SyncEntity.class, SyncControl.class, PendingOperation.class
        },
        version = 14,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    @NonNull
    public abstract SharingDao sharingDao();

    @NonNull
    public abstract HouseholdDao householdDao();

    @NonNull
    public abstract AccountDao accountDao();

    @NonNull
    public abstract GroceryDao groceryDao();

    @NonNull
    public abstract BudgetDao budgetDao();

    @NonNull
    public abstract ShoppingListDao shoppingListDao();

    @NonNull
    public abstract ChecklistHistoryDao checklistHistoryDao();

    @NonNull
    public static AppDatabase getDatabase(@NonNull Context context) {
        AppDatabase current = INSTANCE;
        if (current != null) {
            return current;
        }
        synchronized (AppDatabase.class) {
            if (INSTANCE == null) {
                INSTANCE = Room.databaseBuilder(
                                context.getApplicationContext(), AppDatabase.class, "smart_grocery_database")
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                                MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                        .addCallback(new RoomDatabase.Callback() {
                            @Override
                            public void onCreate(@NonNull SupportSQLiteDatabase db) {
                                SharingSchema.install(db);
                            }
                        })
                        .build();
            }
            return INSTANCE;
        }
    }

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_logs` ( "
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`itemId` INTEGER, "
                    + "`itemName` TEXT NOT NULL, "
                    + "`category` TEXT NOT NULL, "
                    + "`changeType` TEXT NOT NULL, "
                    + "`amountChanged` INTEGER NOT NULL, "
                    + "`unitPriceAtChange` REAL NOT NULL, "
                    + "`timestamp` INTEGER NOT NULL, "
                    + "FOREIGN KEY(`itemId`) REFERENCES `grocery_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL "
                    + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_logs_itemId` ON `inventory_logs` (`itemId`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_logs_timestamp` ON `inventory_logs` (`timestamp`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_logs_changeType` ON `inventory_logs` (`changeType`)");
            db.execSQL("INSERT INTO `inventory_logs` (`itemId`, `itemName`, `category`, `changeType`, `amountChanged`, `unitPriceAtChange`, `timestamp`) "
                    + "SELECT `id`, `name`, `category`, 'ADD', `quantity`, `price`, `purchasedDate` "
                    + "FROM `grocery_items` "
                    + "WHERE `quantity` > 0");

            db.execSQL("CREATE TABLE IF NOT EXISTS `saved_recipes` ( "
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`title` TEXT NOT NULL, "
                    + "`usedIngredients` TEXT NOT NULL, "
                    + "`instructions` TEXT NOT NULL, "
                    + "`prepTimeMinutes` INTEGER NOT NULL, "
                    + "`imageUrl` TEXT NOT NULL, "
                    + "`videoUrl` TEXT NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL "
                    + ")");

            db.execSQL("CREATE TABLE IF NOT EXISTS `budget_entries_new` ( "
                    + "`monthYear` TEXT PRIMARY KEY NOT NULL, "
                    + "`limitAmount` REAL NOT NULL "
                    + ")");
            db.execSQL("INSERT OR REPLACE INTO `budget_entries_new` (`monthYear`, `limitAmount`) "
                    + "SELECT "
                    + "CASE "
                        + "WHEN length(`monthYear`) = 7 AND substr(`monthYear`, 3, 1) = '-' "
                    + "THEN substr(`monthYear`, 4, 4) || '-' || substr(`monthYear`, 1, 2) "
                        + "ELSE `monthYear` "
                    + "END, "
                    + "`limitAmount` "
                    + "FROM `budget_entries`");
            db.execSQL("DROP TABLE `budget_entries`");
            db.execSQL("ALTER TABLE `budget_entries_new` RENAME TO `budget_entries`");
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_list_items` ( "
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`sourceItemId` INTEGER, "
                    + "`itemName` TEXT NOT NULL, "
                    + "`category` TEXT NOT NULL, "
                    + "`quantityNeeded` INTEGER NOT NULL, "
                    + "`quantityLabel` TEXT NOT NULL DEFAULT '', "
                    + "`estimatedUnitPrice` REAL NOT NULL, "
                    + "`addedBy` TEXT NOT NULL DEFAULT 'Auto', "
                    + "`assignedTo` TEXT NOT NULL DEFAULT 'Household', "
                    + "`isChecked` INTEGER NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL "
                    + ")");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_shopping_list_items_sourceItemId` ON `shopping_list_items` (`sourceItemId`)");
        }
    };

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            if (!hasColumn(db, "shopping_list_items", "addedBy")) {
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `addedBy` TEXT NOT NULL DEFAULT 'Auto'");
            }
            if (!hasColumn(db, "shopping_list_items", "assignedTo")) {
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `assignedTo` TEXT NOT NULL DEFAULT 'Household'");
            }
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            if (!hasColumn(db, "shopping_list_items", "quantityLabel")) {
                db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `quantityLabel` TEXT NOT NULL DEFAULT ''");
            }
        }
    };

    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `checklist_history` ( "
                    + "`id` TEXT PRIMARY KEY NOT NULL, "
                    + "`dishName` TEXT NOT NULL, "
                    + "`servings` INTEGER NOT NULL, "
                    + "`status` TEXT NOT NULL, "
                    + "`created_at` INTEGER NOT NULL, "
                    + "`missingChecklistJson` TEXT NOT NULL, "
                    + "`inventoryDeductedJson` TEXT NOT NULL, "
                    + "`addedShoppingKeysJson` TEXT NOT NULL "
                    + ")");
        }
    };

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS `saved_recipes`");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Keep log links while rebuilding the parent table for a nullable expiry.
            db.execSQL("CREATE TEMP TABLE log_links AS SELECT id, itemId FROM inventory_logs");
            db.execSQL("CREATE TABLE grocery_items_new ( "
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, "
                    + "quantity INTEGER NOT NULL, price REAL NOT NULL, category TEXT NOT NULL, "
                    + "expiryDate INTEGER, threshold INTEGER NOT NULL, purchasedDate INTEGER NOT NULL, "
                    + "subcategory TEXT)");
            db.execSQL("INSERT INTO grocery_items_new "
                    + "SELECT id, name, quantity, price, category, "
                    + "CASE WHEN expiryDate > 0 THEN expiryDate ELSE NULL END, threshold, purchasedDate, NULL "
                    + "FROM grocery_items");
            db.execSQL("DROP TABLE grocery_items");
            db.execSQL("ALTER TABLE grocery_items_new RENAME TO grocery_items");
            db.execSQL("UPDATE inventory_logs SET itemId = (SELECT itemId FROM log_links WHERE log_links.id = inventory_logs.id)");
            db.execSQL("DROP TABLE log_links");
            db.execSQL("ALTER TABLE shopping_list_items ADD COLUMN subcategory TEXT");
            for (String table : new String[]{"grocery_items", "shopping_list_items"}) {
                db.execSQL("UPDATE " + table + " SET subcategory = CASE "
                        + "WHEN category IN ('Produce', 'Fruits & Vegetables') THEN 'Produce' "
                        + "WHEN category IN ('Meat', 'Seafood') THEN 'Meat & Seafood' "
                        + "WHEN category IN ('Meat & Seafood', 'Dairy', 'Bakery', 'Pantry', 'Spices', 'Beverages', 'Snacks & Sweets', 'Frozen Foods') THEN category "
                        + "WHEN category IN ('Seasoning', 'Seasonings', 'Spice') THEN 'Spices' "
                        + "WHEN category = 'Snacks' THEN 'Snacks & Sweets' "
                        + "WHEN category = 'Other' THEN 'Pantry' "
                        + "ELSE NULL END");
            }
            for (String table : new String[]{"grocery_items", "shopping_list_items", "inventory_logs"}) {
                db.execSQL("UPDATE " + table + " SET category = CASE "
                        + "WHEN lower(category) IN ('household essentials', 'household', 'cleaning') THEN 'Household Essentials' "
                        + "WHEN lower(category) IN ('personal care', 'toiletries') THEN 'Personal Care' "
                        + "ELSE 'Food & Beverage' END");
            }
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE checklist_history ADD COLUMN stateJson TEXT NOT NULL DEFAULT ''");
            db.execSQL("DELETE FROM checklist_history WHERE id NOT IN (SELECT id FROM checklist_history ORDER BY created_at DESC, id DESC LIMIT 10)");
        }
    };

    static final Migration MIGRATION_9_10 = AccountScopeMigration.INSTANCE;
    static final Migration MIGRATION_10_11 = HouseholdDetailsMigration.INSTANCE;
    static final Migration MIGRATION_11_12 = SharingSchema.MIGRATION_11_12;
    static final Migration MIGRATION_12_13 = SharedItemAttributionMigration.INSTANCE;

    public static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            if (!hasColumn(db, "grocery_items", "unit"))
                db.execSQL("ALTER TABLE grocery_items ADD COLUMN unit TEXT NOT NULL DEFAULT 'pcs'");
            if (!hasColumn(db, "grocery_items", "storageLocation"))
                db.execSQL("ALTER TABLE grocery_items ADD COLUMN storageLocation TEXT NOT NULL DEFAULT 'Not set'");
            if (!hasColumn(db, "inventory_logs", "unit"))
                db.execSQL("ALTER TABLE inventory_logs ADD COLUMN unit TEXT NOT NULL DEFAULT 'pcs'");
        }
    };

    private static boolean hasColumn(SupportSQLiteDatabase db, String tableName, String columnName) {
        try (Cursor cursor = db.query("PRAGMA table_info(`" + tableName + "`)")) {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && columnName.equals(cursor.getString(nameIndex))) {
                    return true;
                }
            }
        }
        return false;
    }
}

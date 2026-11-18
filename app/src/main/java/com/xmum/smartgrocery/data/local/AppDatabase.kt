package com.xmum.smartgrocery.data.local

import com.xmum.smartgrocery.data.sharing.*
import android.content.Context
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.local.dao.HouseholdDao
import com.xmum.smartgrocery.data.local.dao.AccountDao
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xmum.smartgrocery.data.local.dao.BudgetDao
import com.xmum.smartgrocery.data.local.dao.ChecklistHistoryDao
import com.xmum.smartgrocery.data.local.dao.GroceryDao
import com.xmum.smartgrocery.data.local.dao.ShoppingListDao
import com.xmum.smartgrocery.data.local.entity.BudgetEntry
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GroceryItem::class, BudgetEntry::class, InventoryLog::class, ShoppingListItem::class, ChecklistHistoryItem::class, UserEntity::class, HouseholdEntity::class, HouseholdMemberEntity::class, LocalSessionEntity::class, HouseholdSettingEntity::class, CloudAccount::class, CloudUser::class, CloudHousehold::class, SyncEntity::class, SyncControl::class, PendingOperation::class],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sharingDao(): SharingDao
    abstract fun householdDao(): HouseholdDao
    abstract fun accountDao(): AccountDao
    abstract fun groceryDao(): GroceryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun checklistHistoryDao(): ChecklistHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_grocery_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { SharingSchema.install(db) }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `inventory_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itemId` INTEGER,
                        `itemName` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `changeType` TEXT NOT NULL,
                        `amountChanged` INTEGER NOT NULL,
                        `unitPriceAtChange` REAL NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        FOREIGN KEY(`itemId`) REFERENCES `grocery_items`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_logs_itemId` ON `inventory_logs` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_logs_timestamp` ON `inventory_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_logs_changeType` ON `inventory_logs` (`changeType`)")
                db.execSQL(
                    """
                    INSERT INTO `inventory_logs` (`itemId`, `itemName`, `category`, `changeType`, `amountChanged`, `unitPriceAtChange`, `timestamp`)
                    SELECT `id`, `name`, `category`, 'ADD', `quantity`, `price`, `purchasedDate`
                    FROM `grocery_items`
                    WHERE `quantity` > 0
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_recipes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `usedIngredients` TEXT NOT NULL,
                        `instructions` TEXT NOT NULL,
                        `prepTimeMinutes` INTEGER NOT NULL,
                        `imageUrl` TEXT NOT NULL,
                        `videoUrl` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `budget_entries_new` (
                        `monthYear` TEXT PRIMARY KEY NOT NULL,
                        `limitAmount` REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `budget_entries_new` (`monthYear`, `limitAmount`)
                    SELECT
                        CASE
                            WHEN length(`monthYear`) = 7 AND substr(`monthYear`, 3, 1) = '-'
                            THEN substr(`monthYear`, 4, 4) || '-' || substr(`monthYear`, 1, 2)
                            ELSE `monthYear`
                        END,
                        `limitAmount`
                    FROM `budget_entries`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `budget_entries`")
                db.execSQL("ALTER TABLE `budget_entries_new` RENAME TO `budget_entries`")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shopping_list_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceItemId` INTEGER,
                        `itemName` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `quantityNeeded` INTEGER NOT NULL,
                        `quantityLabel` TEXT NOT NULL DEFAULT '',
                        `estimatedUnitPrice` REAL NOT NULL,
                        `addedBy` TEXT NOT NULL DEFAULT 'Auto',
                        `assignedTo` TEXT NOT NULL DEFAULT 'Household',
                        `isChecked` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_shopping_list_items_sourceItemId` ON `shopping_list_items` (`sourceItemId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("shopping_list_items", "addedBy")) {
                    db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `addedBy` TEXT NOT NULL DEFAULT 'Auto'")
                }
                if (!db.hasColumn("shopping_list_items", "assignedTo")) {
                    db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `assignedTo` TEXT NOT NULL DEFAULT 'Household'")
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("shopping_list_items", "quantityLabel")) {
                    db.execSQL("ALTER TABLE `shopping_list_items` ADD COLUMN `quantityLabel` TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `checklist_history` (
                        `id` TEXT PRIMARY KEY NOT NULL,
                        `dishName` TEXT NOT NULL,
                        `servings` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `missingChecklistJson` TEXT NOT NULL,
                        `inventoryDeductedJson` TEXT NOT NULL,
                        `addedShoppingKeysJson` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `saved_recipes`")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep log links while rebuilding the parent table for a nullable expiry.
                db.execSQL("CREATE TEMP TABLE log_links AS SELECT id, itemId FROM inventory_logs")
                db.execSQL("""CREATE TABLE grocery_items_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL,
                    quantity INTEGER NOT NULL, price REAL NOT NULL, category TEXT NOT NULL,
                    expiryDate INTEGER, threshold INTEGER NOT NULL, purchasedDate INTEGER NOT NULL,
                    subcategory TEXT)""")
                db.execSQL("""INSERT INTO grocery_items_new
                    SELECT id, name, quantity, price, category,
                    CASE WHEN expiryDate > 0 THEN expiryDate ELSE NULL END, threshold, purchasedDate, NULL
                    FROM grocery_items""")
                db.execSQL("DROP TABLE grocery_items")
                db.execSQL("ALTER TABLE grocery_items_new RENAME TO grocery_items")
                db.execSQL("UPDATE inventory_logs SET itemId = (SELECT itemId FROM log_links WHERE log_links.id = inventory_logs.id)")
                db.execSQL("DROP TABLE log_links")
                db.execSQL("ALTER TABLE shopping_list_items ADD COLUMN subcategory TEXT")
                for (table in listOf("grocery_items", "shopping_list_items")) {
                    db.execSQL("""UPDATE $table SET subcategory = CASE
                        WHEN category IN ('Produce', 'Fruits & Vegetables') THEN 'Produce'
                        WHEN category IN ('Meat', 'Seafood') THEN 'Meat & Seafood'
                        WHEN category IN ('Meat & Seafood', 'Dairy', 'Bakery', 'Pantry', 'Spices', 'Beverages', 'Snacks & Sweets', 'Frozen Foods') THEN category
                        WHEN category IN ('Seasoning', 'Seasonings', 'Spice') THEN 'Spices'
                        WHEN category = 'Snacks' THEN 'Snacks & Sweets'
                        WHEN category = 'Other' THEN 'Pantry'
                        ELSE NULL END""")
                }
                for (table in listOf("grocery_items", "shopping_list_items", "inventory_logs")) {
                    db.execSQL("""UPDATE $table SET category = CASE
                        WHEN lower(category) IN ('household essentials', 'household', 'cleaning') THEN 'Household Essentials'
                        WHEN lower(category) IN ('personal care', 'toiletries') THEN 'Personal Care'
                        ELSE 'Food & Beverage' END""")
                }
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE checklist_history ADD COLUMN stateJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("DELETE FROM checklist_history WHERE id NOT IN (SELECT id FROM checklist_history ORDER BY created_at DESC, id DESC LIMIT 10)")
            }
        }

        internal val MIGRATION_12_13: Migration = SharedItemAttributionMigration

        internal val MIGRATION_11_12: Migration = SharingSchema.MIGRATION_11_12

        internal val MIGRATION_10_11: Migration = HouseholdDetailsMigration

        internal val MIGRATION_9_10: Migration = AccountScopeMigration

        private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }
    }
}

package com.xmum.smartgrocery.data.local

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses an archived v6 schema and temporary databases, never the user's production database. */
@RunWith(AndroidJUnit4::class)
class RemoveSavedRecipesMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val activeTables = listOf("grocery_items", "budget_entries", "inventory_logs", "shopping_list_items", "checklist_history")

    @Test fun version6UpgradePreservesActiveDataAndMigratesCategoryHierarchy(): Unit = runBlocking {
        val name = "saved-recipes-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        var migrated: AppDatabase? = null
        try {
            val originalRows: Map<String, List<List<String?>>>
            val originalSchema: List<List<String?>>
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val fixture = instrumentation.context.assets.open("database/schema-v6.sql").bufferedReader().use { it.readText() }
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                fixture.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(old::execSQL)
                old.execSQL("INSERT INTO grocery_items VALUES (41, 'Milk / 牛奶', 2, 3.5, 'Dairy', 1900000000000, 2, 1800000000000)")
                old.execSQL("INSERT INTO grocery_items VALUES (42, 'Eggs', 0, 1.2, 'Dairy', 1700000000000, 1, 1600000000000)")
                old.execSQL("INSERT INTO budget_entries VALUES ('2026-10', 250.75)")
                old.execSQL("INSERT INTO inventory_logs VALUES (51, 41, 'Milk / 牛奶', 'Dairy', 'ADD', 2, 3.5, 1800000000000)")
                old.execSQL("INSERT INTO inventory_logs VALUES (52, NULL, 'Deleted item', 'Pantry', 'DEDUCT', 1, 2.0, 1800000000001)")
                old.execSQL("INSERT INTO shopping_list_items VALUES (61, 41, 'Milk / 牛奶', 'Dairy', 3, '3 bottles', 3.5, 'Voice', 'Me', 0, 1800000000000, 1800000000001)")
                old.execSQL("INSERT INTO shopping_list_items VALUES (62, NULL, 'Eggs', 'Dairy', 2, '2 pcs', 1.2, 'Manual', 'Household', 1, 1800000000002, 1800000000003)")
                old.execSQL("INSERT INTO checklist_history VALUES ('check-1', 'Egg rice', 2, 'pending', 1800000000000, '[]', '[]', '[\"eggs\"]')")
                old.execSQL("INSERT INTO checklist_history VALUES ('check-2', 'Milk pudding', 1, 'cooked', 1800000000001, '[]', '[]', '[]')")
                old.execSQL("INSERT INTO saved_recipes VALUES (71, 'Legacy recipe', '[]', '[]', 20, '', '', 1800000000000)")
                old.version = 6
                originalRows = snapshot { sql -> old.rawQuery(sql, null) }
                originalSchema = schema { sql -> old.rawQuery(sql, null) }
            }
            val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13).build()
            migrated = database
            val sqlite = database.openHelper.writableDatabase
            database.inspectLegacyAccount() // Room validates the complete v7 schema here.
            assertEquals(13, sqlite.version)
            val expectedRows = originalRows.mapValues { (table, rows) -> rows.map { row ->
                when (table) {
                    "grocery_items" -> row.toMutableList().apply { val leaf = this[4]; this[4] = "Food & Beverage"; add(leaf) }
                    "shopping_list_items" -> row.toMutableList().apply { val leaf = this[3]; this[3] = "Food & Beverage"; add(leaf) }
                    "inventory_logs" -> row.toMutableList().apply { this[3] = "Food & Beverage" }
                    "checklist_history" -> row + ""
                    else -> row
                }
            } }
            val scopedRows = expectedRows.mapValues { (table, rows) -> rows.map { row ->
                when (table) {
                    "budget_entries" -> row + listOf("u:legacy-unclaimed", "legacy-unclaimed", null)
                    "checklist_history" -> row + "legacy-unclaimed"
                    else -> row + listOf("legacy-unclaimed", null)
                }
            } }
            assertEquals(scopedRows, snapshot(sqlite::query))
            sqlite.query("SELECT name FROM sqlite_master WHERE name = 'saved_recipes'").use { assertFalse(it.moveToFirst()) }
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertEquals(250.75, database.budgetDao().getBudgetForMonthSync("2026-10")!!.limitAmount, 0.0)
            assertEquals("Milk / 牛奶", database.groceryDao().getItemById(41)!!.name)
            assertEquals("pending", database.checklistHistoryDao().getChecklistHistoryById("check-1")!!.status)
            // Preserved autoincrement IDs must still work after migration, including inventory logging.
            val inserted = database.groceryDao().insertWithLog(GroceryItem(name = "New item", quantity = 1,
                price = 2.0, category = "Other", expiryDate = 1900000000000))
            assertTrue(inserted > 42)
            assertEquals(3, database.groceryDao().getAllItemsOnce().size)
            database.close(); migrated = null
            // Reopening must pass Room's identity check too.
            migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(13, migrated.openHelper.writableDatabase.version)
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun freshDatabaseContainsFeatureAndAccountTables() {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(13, sqlite.version)
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name").use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                assertEquals((activeTables + listOf("users", "households", "household_members", "local_session", "household_settings", "cloud_accounts", "cloud_users", "cloud_households", "sync_entities", "sync_control", "sync_outbox")).sorted(), names)
            }
        } finally { database.close() }
    }

    private fun snapshot(query: (String) -> Cursor): Map<String, List<List<String?>>> = activeTables.associateWith {
        query("SELECT * FROM `$it` ORDER BY 1").use(::rows)
    }

    private fun schema(query: (String) -> Cursor): List<List<String?>> = query(
        "SELECT name, sql FROM sqlite_master WHERE type IN ('table', 'index') AND name != 'saved_recipes' ORDER BY name"
    ).use(::rows)

    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
    }
}

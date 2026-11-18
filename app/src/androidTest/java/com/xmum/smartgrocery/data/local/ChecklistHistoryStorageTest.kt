package com.xmum.smartgrocery.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ChecklistHistoryStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun concurrentInsertAndPruneAreAtomicAndUpdatingAtCapacityKeepsOtherRows(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.useTestAccount()
            val dao = db.checklistHistoryDao()
            (1..20).map { n -> async { dao.saveWithPruning(ChecklistHistoryItem("meal_$n", "Beef Noodle", 2, "pending", n.toLong())) } }.awaitAll()
            assertEquals(10, dao.getCount())
            assertEquals((20L downTo 11L).toList(), dao.getAllChecklistHistorySync().map { it.createdAt })
            val oldest = dao.getChecklistHistoryById("meal_11")!!
            dao.saveWithPruning(oldest.copy(status = "cooked"))
            assertEquals(10, dao.getCount())
            assertEquals("cooked", dao.getChecklistHistoryById("meal_11")!!.status)
            // Failure after INSERT must roll the entire operation back, including retention.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_history_delete BEFORE DELETE ON checklist_history BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
            assertTrue(runCatching { dao.saveWithPruning(ChecklistHistoryItem("meal_21", "Beef Noodle", 2, "pending", 21L)) }.isFailure)
            assertNull(dao.getChecklistHistoryById("meal_21"))
            assertEquals(10, dao.getCount())
            assertNotNull(dao.getChecklistHistoryById("meal_11"))
        } finally { db.close() }
    }

    @Test fun version8UpgradePreservesAllTablesAndAddsEmptySnapshotForLegacyBackfill(): Unit = runBlocking {
        val name = "history-snapshot-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        var db: AppDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("database/schema-v8.sql").bufferedReader().use { it.readText() }
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(old::execSQL)
                old.execSQL("INSERT INTO checklist_history VALUES ('old-1', 'Beef Noodle', 2, 'pending', 1000, '[]', '[]', '[]')")
                old.execSQL("INSERT INTO checklist_history VALUES ('old-2', 'Beef Noodle', 4, 'cooked', 2000, '[]', '[]', '[]')")
                old.execSQL("INSERT INTO grocery_items VALUES (41, 'Eggs', 3, 2.5, 'Food & Beverage', 1900000000000, 0, 1800000000000, 'Dairy')")
                old.execSQL("INSERT INTO budget_entries VALUES ('2026-10', 250.75)")
                old.execSQL("INSERT INTO shopping_list_items VALUES (61, 41, 'Eggs', 'Food & Beverage', 2, '2 pcs', 2.5, 'Voice', 'Me', 0, 1800000000000, 1800000000001, 'Dairy')")
                old.execSQL("INSERT INTO inventory_logs VALUES (51, 41, 'Eggs', 'Food & Beverage', 'ADD', 3, 2.5, 1800000000000)")
                old.version = 8
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13).build(); db = migrated
            assertEquals(13, migrated.openHelper.writableDatabase.version)
            migrated.inspectLegacyAccount()
            assertEquals(listOf("old-2", "old-1"), migrated.checklistHistoryDao().getAllChecklistHistorySync().map { it.id })
            assertTrue(migrated.checklistHistoryDao().getAllChecklistHistorySync().all { it.stateJson.isEmpty() })
            assertEquals(3, migrated.groceryDao().getItemById(41)!!.quantity)
            assertEquals(250.75, migrated.budgetDao().getBudgetForMonthSync("2026-10")!!.limitAmount, 0.0)
            migrated.openHelper.readableDatabase.query("SELECT quantityNeeded, assignedTo FROM shopping_list_items WHERE id=61").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)); assertEquals("Me", it.getString(1))
            }
            migrated.openHelper.readableDatabase.query("SELECT itemId FROM inventory_logs WHERE id=51").use { assertTrue(it.moveToFirst()); assertEquals(41,it.getInt(0)) }
            migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            migrated.close(); db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(13, db!!.openHelper.writableDatabase.version)
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}

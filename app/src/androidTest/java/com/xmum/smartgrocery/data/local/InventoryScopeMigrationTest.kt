package com.xmum.smartgrocery.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.utils.DateUtils
import com.xmum.smartgrocery.utils.GroceryCategories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InventoryScopeMigrationTest {
    @Test fun version7UpgradePreservesLogLinksAndUndatedItemsSurviveCleanup(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "inventory-scope-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        var database: AppDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val fixture = instrumentation.context.assets.open("database/schema-v6.sql").bufferedReader().use { it.readText() }
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                fixture.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(old::execSQL)
                old.execSQL("DROP TABLE saved_recipes")
                old.execSQL("INSERT INTO grocery_items VALUES (41, 'Toilet Paper', 3, 6.5, 'Household Essentials', 0, 1, 1800000000000)")
                old.execSQL("INSERT INTO grocery_items VALUES (42, 'Shampoo', 2, 8.0, 'Personal Care', 1900000000000, 1, 1800000000000)")
                old.execSQL("INSERT INTO grocery_items VALUES (43, 'Tomato', 4, 2.0, 'Fruits & Vegetables', 1, 2, 1800000000000)")
                old.execSQL("INSERT INTO grocery_items VALUES (44, 'Water', 1, 1.0, 'Beverages', 1900000000000, 2, 1800000000000)")
                old.execSQL("INSERT INTO grocery_items VALUES (45, 'Chips', 1, 2.0, 'Snacks', 1900000000000, 2, 1800000000000)")
                old.execSQL("INSERT INTO grocery_items VALUES (46, 'Frozen dumplings', 1, 3.0, 'Frozen Foods', 1900000000000, 2, 1800000000000)")
                old.execSQL("INSERT INTO inventory_logs VALUES (51, 41, 'Toilet Paper', 'Household Essentials', 'ADD', 3, 6.5, 1800000000000)")
                old.version = 7
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13).build()
            database = migrated
            val sqlite = migrated.openHelper.writableDatabase // Validate the actual Room schema.
            assertEquals(13, sqlite.version)
            migrated.inspectLegacyAccount()
            val dao = migrated.groceryDao()
            assertNull(dao.getItemById(41)!!.expiryDate)
            assertNull(dao.getItemById(41)!!.subcategory)
            assertEquals(1900000000000L, dao.getItemById(42)!!.expiryDate)
            assertEquals("Produce", dao.getItemById(43)!!.subcategory)
            assertEquals("Beverages", dao.getItemById(44)!!.subcategory)
            assertEquals("Snacks & Sweets", dao.getItemById(45)!!.subcategory)
            assertEquals("Frozen Foods", dao.getItemById(46)!!.subcategory)
            sqlite.query("SELECT itemId FROM inventory_logs WHERE id = 51").use {
                assertTrue(it.moveToFirst()); assertEquals(41, it.getInt(0))
            }
            assertEquals(1, dao.deleteExpiredItems(DateUtils.getStartOfToday()))
            assertNotNull(dao.getItemById(41))
            val newId = dao.insertWithLog(GroceryItem(name = "Dishwashing detergent", quantity = 2, price = 4.0,
                category = "Household Essentials", subcategory = "Pantry")).toInt()
            assertNull(dao.getItemById(newId)!!.expiryDate)
            assertNull(dao.getItemById(newId)!!.subcategory)
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun allNineFoodLabelsRoundTripThroughExistingVersion9TablesAndReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "food-subcategories-${UUID.randomUUID()}.db"
        var database: AppDatabase? = null
        try {
            val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            database = initial
            initial.useTestAccount()
            val labels = GroceryCategories.foodSubcategories
            val ids = initial.groceryDao().insertBatchWithLogs(labels.map { label ->
                GroceryItem(name = label, category = "Food & Beverage", subcategory = label,
                    quantity = 2, price = 3.0, expiryDate = 1900000000000L)
            })
            for (label in labels) initial.shoppingListDao().insertManual(ShoppingListItem(
                itemName = "Buy $label", category = label, quantityNeeded = 1, estimatedUnitPrice = 2.0))
            val pantryId = initial.groceryDao().insertWithLog(GroceryItem(name = "Previously saved coffee",
                category = "Pantry", quantity = 1, price = 4.0)).toInt()
            initial.close()
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            database = reopened
            assertEquals(13, reopened.openHelper.writableDatabase.version)
            for ((index, label) in labels.withIndex()) {
                val item = reopened.groceryDao().getItemById(ids[index].toInt())!!
                assertEquals("Food & Beverage", item.category)
                assertEquals(label, item.subcategory)
                assertEquals(1900000000000L, item.expiryDate)
            }
            val shopping = reopened.shoppingListDao().getShoppingListItems().first()
            assertEquals(labels.toSet(), shopping.map { it.subcategory }.toSet())
            assertTrue(shopping.all { it.category == "Food & Beverage" })
            assertEquals("Pantry", reopened.groceryDao().getItemById(pantryId)!!.subcategory)
            reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }
}

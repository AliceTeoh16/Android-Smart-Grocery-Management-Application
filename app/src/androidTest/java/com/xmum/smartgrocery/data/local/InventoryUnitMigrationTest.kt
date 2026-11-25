package com.xmum.smartgrocery.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.data.sharing.CloudHousehold
import com.xmum.smartgrocery.data.sharing.PendingOperation
import com.xmum.smartgrocery.data.sharing.SharingSchema
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InventoryUnitMigrationTest {
    @Test fun version13PreservesPrivateAndSharedDataPurchaseTotalsAndPendingSync(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "unit-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            db.useTestAccount("unit-migration")
            val personal = db.groceryDao().insertWithLog(GroceryItem(name="Private rice", quantity=2, price=3.0, category="Pantry")).toInt()
            val home = SessionManager(db).createHousehold("Unit family")
            SharingSchema.install(db.openHelper.writableDatabase)
            db.sharingDao().household(CloudHousehold(home.householdId, "http://127.0.0.1:8791/", "unit-home"))
            val shared = db.groceryDao().insertWithLog(GroceryItem(name="Eggs", quantity=12, price=1.0, category="Dairy")).toInt()
            val pending = PendingOperation("unit-pending", home.householdId, "mark", "data", "mutation")
            db.sharingDao().enqueue(pending)
            val marks = db.sharingDao().marks(home.householdId)
            val purchases = db.groceryDao().getPurchaseHistory().first()
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("ALTER TABLE grocery_items DROP COLUMN unit")
                it.execSQL("ALTER TABLE grocery_items DROP COLUMN storageLocation")
                it.execSQL("ALTER TABLE inventory_logs DROP COLUMN unit")
                it.version = 13
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_13_14).build()
            val migrated = db.groceryDao().getItemById(shared)!!
            assertEquals(12, migrated.quantity); assertEquals("pcs", migrated.unit); assertEquals("Not set", migrated.storageLocation)
            assertEquals(marks, db.sharingDao().marks(home.householdId))
            assertEquals(listOf(pending), db.sharingDao().outbox(home.householdId))
            assertEquals(purchases, db.groceryDao().getPurchaseHistory().first())
            db.groceryDao().update(migrated.copy(storageLocation="Fridge"))
            assertEquals("Fridge", db.groceryDao().getItemById(shared)!!.storageLocation)
            SessionManager(db).selectHousehold(null)
            assertEquals("pcs", db.groceryDao().getItemById(personal)!!.unit)
            assertNull(db.groceryDao().getItemById(shared))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

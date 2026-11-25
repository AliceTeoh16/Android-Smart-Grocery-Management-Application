package com.xmum.smartgrocery.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.data.sharing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharedItemAttributionStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun account(db: AppDatabase, id: String, name: String) {
        db.accountDao().insertUser(UserEntity(id, "$id@example.test", name, "", "", 600000))
        db.accountDao().setSession(LocalSessionEntity(userId = id))
    }
    private fun stock() = GroceryItem(name = "Milk", category = "Dairy", quantity = 4, price = 2.0)
    private fun shopping() = ShoppingListItem(itemName = "Eggs", category = "Dairy", quantityNeeded = 2,
        estimatedUnitPrice = 3.0, addedBy = "Voice", assignedTo = "Household")

    @Test fun householdWritesStampTheActorPreserveCreatorAndKeepInputSource(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            account(db, "alice", "Alice")
            val manager = SessionManager(db)
            val home = manager.createHousehold("Family")
            val id = db.groceryDao().insert(stock().copy(addedByName = "Forged", lastModifiedByName = "Forged")).toInt()
            val listId = db.shoppingListDao().insertManual(shopping().copy(addedByName = "Forged")).toInt()
            assertEquals("Added by Alice", db.groceryDao().getItemById(id)!!.sharedChangeIndicator)
            assertEquals("Added by Alice", db.shoppingListDao().getById(listId)!!.sharedChangeIndicator)
            account(db, "ben", "Ben"); manager.joinHousehold(home.joinCode)
            val original = db.groceryDao().getItemById(id)!!
            db.groceryDao().update(original.copy(price = 4.0, addedByName = "Forged", lastModifiedByName = "Forged"))
            assertEquals("Alice", db.groceryDao().getItemById(id)!!.addedByName)
            assertEquals("Updated by Ben", db.groceryDao().getItemById(id)!!.sharedChangeIndicator)
            db.groceryDao().updateQuantity(id, 6)
            db.groceryDao().deductQuantity(id, 1)
            assertEquals("Ben", db.groceryDao().getItemById(id)!!.lastModifiedByName)
            assertEquals(5, db.groceryDao().getItemById(id)!!.quantity)
            db.shoppingListDao().setChecked(listId, true)
            var row = db.shoppingListDao().getById(listId)!!
            assertEquals("Checked by Ben", row.sharedChangeIndicator)
            assertEquals("Alice", row.addedByName); assertEquals("Voice", row.addedBy)
            assertNotNull(row.lastModifiedAt)
            db.shoppingListDao().setChecked(listId, false)
            assertEquals("Unchecked by Ben", db.shoppingListDao().getById(listId)!!.sharedChangeIndicator)
            row = db.shoppingListDao().getById(listId)!!
            db.shoppingListDao().update(row.copy(quantityNeeded = 5, addedByName = "Forged"))
            assertEquals("Updated by Ben", db.shoppingListDao().getById(listId)!!.sharedChangeIndicator)
            assertEquals("Alice", db.shoppingListDao().getById(listId)!!.addedByName)
        } finally { db.close() }
    }

    @Test fun personalRowsHideTrackingAndNonMembersCannotStampSharedRows(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            account(db, "alice", "Alice")
            val manager = SessionManager(db)
            val personalId = db.groceryDao().insert(stock().copy(addedByName = "Forged", lastModifiedAction = "Added")).toInt()
            val personal = db.groceryDao().getItemById(personalId)!!
            assertNull(personal.addedByName); assertNull(personal.sharedChangeIndicator)
            val privateListId = db.shoppingListDao().insertManual(shopping().copy(assignedTo = "Me")).toInt()
            assertNull(db.shoppingListDao().getById(privateListId)!!.sharedChangeIndicator)
            val home = manager.createHousehold("Family")
            db.shoppingListDao().assignTo(privateListId, "Household")
            assertEquals("Added by Alice", db.shoppingListDao().getById(privateListId)!!.sharedChangeIndicator)
            val sharedId = db.groceryDao().insert(stock()).toInt()
            val shared = db.groceryDao().getItemById(sharedId)!!
            account(db, "eve", "Eve")
            db.groceryDao().updateQuantity(sharedId, 99)
            assertEquals(0, db.groceryDao().update(shared.copy(price = 99.0)))
            db.shoppingListDao().setChecked(privateListId, true)
            db.accountDao().setSession(LocalSessionEntity(userId = "alice", householdId = home.householdId))
            assertEquals(shared, db.groceryDao().getItemById(sharedId))
            assertEquals("Added by Alice", db.shoppingListDao().getById(privateListId)!!.sharedChangeIndicator)
            manager.selectHousehold(null)
            db.shoppingListDao().assignTo(privateListId, "Me")
            assertNull(db.shoppingListDao().getById(privateListId)!!.addedByName)
            assertNull(db.shoppingListDao().getById(privateListId)!!.sharedChangeIndicator)
        } finally { db.close() }
    }

    @Test fun version12MigrationPreservesDataAndOutboxWithoutInventingAnEditor(): Unit = runBlocking {
        val name = "author-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            account(db, "alice", "Alice")
            val personalId = db.groceryDao().insertWithLog(stock()).toInt()
            val home = SessionManager(db).createHousehold("Family")
            SharingSchema.install(db.openHelper.writableDatabase)
            db.sharingDao().household(CloudHousehold(home.householdId, "http://127.0.0.1:8791/", "remote-home"))
            val sharedId = db.groceryDao().insertWithLog(stock()).toInt()
            val listId = db.shoppingListDao().insertManual(shopping()).toInt()
            val pending = PendingOperation("pending", home.householdId, "mark", "data", "mutation")
            db.sharingDao().enqueue(pending)
            val marks = db.sharingDao().marks(home.householdId)
            val purchases = db.groceryDao().getPurchaseHistory().first()
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
                for (table in listOf("grocery_items", "shopping_list_items"))
                    for (column in listOf("addedByName", "lastModifiedByName", "lastModifiedAction", "lastModifiedAt"))
                        legacy.execSQL("ALTER TABLE $table DROP COLUMN $column")
                legacy.version = 12
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14).build()
            assertEquals("Added by Alice", db.groceryDao().getItemById(sharedId)!!.sharedChangeIndicator)
            assertNull(db.groceryDao().getItemById(sharedId)!!.lastModifiedByName)
            assertEquals("Added by Alice", db.shoppingListDao().getById(listId)!!.sharedChangeIndicator)
            assertEquals(marks, db.sharingDao().marks(home.householdId))
            assertEquals(listOf(pending), db.sharingDao().outbox(home.householdId))
            assertEquals(purchases, db.groceryDao().getPurchaseHistory().first())
            SessionManager(db).selectHousehold(null)
            assertNull(db.groceryDao().getItemById(personalId)!!.sharedChangeIndicator)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

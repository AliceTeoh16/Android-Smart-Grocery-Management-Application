package com.xmum.smartgrocery.data.local

import com.xmum.smartgrocery.data.session.withInventoryDestination
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RestockApprovalStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun lowStockRefreshNeverAddsAndApprovalIsIdempotent(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.useTestAccount("alice")
            val stock = db.groceryDao()
            val list = db.shoppingListDao()
            val id = stock.insert(GroceryItem(name = "Eggs", category = "Dairy", quantity = 0, threshold = 2, price = 1.0)).toInt()
            repeat(3) { list.syncLowStockItems(stock.getAllItemsOnce()) }
            assertTrue(list.getShoppingListItems().first().isEmpty())
            val results = List(4) { async { list.approveRestocks(mapOf(id to 3)) } }.awaitAll()
            assertEquals(1, results.sum())
            val row = list.getShoppingListItems().first().single()
            assertEquals("Restock", row.addedBy); assertEquals(3, row.quantityNeeded)
            assertEquals("alice", row.userId); assertNull(row.householdId)
            stock.updateQuantityWithLog(id, 8)
            assertEquals(0, list.approveRestocks(mapOf(id to 2)))
            list.syncLowStockItems(stock.getAllItemsOnce())
            assertEquals(row, list.getShoppingListItems().first().single())
        } finally { db.close() }
    }

    @Test fun approvalDoesNotOverwriteAnExistingPlanAndAllowsFutureRestocks(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.useTestAccount()
            val id = db.groceryDao().insert(GroceryItem(name = "Milk", category = "Dairy", quantity = 1, threshold = 2, price = 1.0)).toInt()
            val dao = db.shoppingListDao()
            val manual = ShoppingListItem(itemName = "Milk", category = "Dairy", quantityNeeded = 8,
                estimatedUnitPrice = 1.0, assignedTo = "Me", addedBy = "Manual")
            val manualId = dao.insertManual(manual).toInt()
            assertEquals(0, dao.approveRestocks(mapOf(id to 2)))
            assertEquals(8, dao.getById(manualId)!!.quantityNeeded)
            dao.delete(dao.getById(manualId)!!)
            assertEquals(1, dao.approveRestocks(mapOf(id to 2)))
            dao.setChecked(dao.getShoppingListItems().first().single().id, true)
            assertEquals(1, dao.approveRestocks(mapOf(id to 4)))
            val rows = dao.getShoppingListItems().first()
            assertEquals(2, rows.size); assertNull(rows.first { it.isChecked }.sourceItemId)
            assertEquals(4, rows.first { !it.isChecked }.quantityNeeded)
        } finally { db.close() }
    }

    @Test fun chosenInventoryAndRestocksStayInTheirReviewedPersonalOrHouseholdScope(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.useTestAccount("alice")
            val manager = SessionManager(db)
            val first = manager.createHousehold("First home")
            val second = manager.createHousehold("Second home")
            val personal = DataScope("alice", null)
            val shared = DataScope("alice", first.householdId)
            val privateId = manager.withInventoryDestination(personal) {
                db.groceryDao().insertWithLog(GroceryItem(name = "Private milk", category = "Dairy", quantity = 1, price = 2.0))
            }.toInt()
            val sharedId = manager.withInventoryDestination(shared) {
                db.groceryDao().insertWithLog(GroceryItem(name = "Shared eggs", category = "Dairy", quantity = 1, price = 3.0))
            }.toInt()
            manager.selectHousehold(second.householdId)
            manager.withInventoryDestination(shared) { db.shoppingListDao().approveRestocks(mapOf(sharedId to 5, privateId to 2)) }
            assertEquals(listOf("Shared eggs"), db.shoppingListDao().getShoppingListItems().first().map { it.itemName })
            manager.withInventoryDestination(personal) { db.shoppingListDao().approveRestocks(mapOf(privateId to 2)) }
            assertEquals(listOf("Private milk"), db.shoppingListDao().getShoppingListItems().first().map { it.itemName })
            db.useTestAccount("bob"); manager.joinHousehold(first.joinCode)
            assertEquals(listOf("Shared eggs"), db.groceryDao().getAllItemsOnce().map { it.name })
            assertEquals(listOf("Shared eggs"), db.shoppingListDao().getShoppingListItems().first().map { it.itemName })
            manager.selectHousehold(null)
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
            var blocked = false
            try { manager.withInventoryDestination(personal) { error("Must not execute") } }
            catch (_: IllegalStateException) { blocked = true }
            assertTrue(blocked)
            assertEquals("bob", manager.currentUserId())
            assertNull(manager.currentSession()!!.householdId)
        } finally { db.close() }
    }

    @Test fun invalidDestinationsAndFailedBatchesCannotRedirectOrPartiallySave(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.useTestAccount("alice")
            val manager = SessionManager(db)
            val home = manager.createHousehold("Original home")
            assertTrue(runCatching {
                manager.withInventoryDestination(DataScope("alice", "not-a-membership")) {
                    error("Must never reach the write")
                }
            }.isFailure)
            assertEquals(home.householdId, manager.currentSession()!!.householdId)
            assertTrue(runCatching {
                manager.withInventoryDestination(DataScope("alice", null)) {
                    db.groceryDao().insertWithLog(GroceryItem(name = "Rolled back", category = "Dairy", quantity = 1, price = 1.0))
                    error("Simulated second row failure")
                }
            }.isFailure)
            assertEquals(home.householdId, manager.currentSession()!!.householdId)
            manager.selectHousehold(null)
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            assertTrue(db.groceryDao().getPurchaseHistory().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun cleanupRemovesOnlyUnapprovedAutomaticRows(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.useTestAccount()
            val dao = db.shoppingListDao()
            val id = db.groceryDao().insert(GroceryItem(name = "Old auto", category = "Other", quantity = 1, price = 0.0)).toInt()
            dao.insertManual(ShoppingListItem(sourceItemId = id, itemName = "Old auto", category = "Other", quantityNeeded = 1, estimatedUnitPrice = 0.0, assignedTo = "Me"))
            dao.insertManual(ShoppingListItem(itemName = "Keep manual", category = "Other", quantityNeeded = 2, estimatedUnitPrice = 0.0, addedBy = "Manual", assignedTo = "Me"))
            assertEquals(1, dao.removeUnapprovedRestocks())
            assertEquals(listOf("Keep manual"), dao.getShoppingListItems().first().map { it.itemName })
            dao.approveRestocks(mapOf(id to 2))
            assertEquals(0, dao.removeUnapprovedRestocks())
            assertEquals(2, dao.getShoppingListItems().first().size)
        } finally { db.close() }
    }
}

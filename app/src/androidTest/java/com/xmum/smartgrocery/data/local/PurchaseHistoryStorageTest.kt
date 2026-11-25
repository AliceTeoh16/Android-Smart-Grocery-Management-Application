package com.xmum.smartgrocery.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.session.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PurchaseHistoryStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun historySurvivesInventoryDeletionPriceChangesAndDatabaseReopen(): Unit = runBlocking {
        val name = "purchase-history-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            db.useTestAccount("buyer")
            val dao = db.groceryDao()
            val id = dao.insertWithLog(GroceryItem(name = "Milk", quantity = 2, price = 3.5,
                category = "Dairy", purchasedDate = 1000)).toInt()
            dao.update(dao.getItemById(id)!!.copy(price = 4.0))
            dao.updateQuantityWithLog(id, 3, null, 2000)
            dao.updateQuantityWithLog(id, 1, null, 3000)
            dao.delete(dao.getItemById(id)!!)
            val records = dao.getPurchaseHistory().first()
            assertEquals(listOf(2000L, 1000L), records.map { it.timestamp })
            assertEquals(listOf(4.0, 3.5), records.map { it.unitPriceAtChange })
            assertEquals(11.0, records.sumOf { it.amountChanged * it.unitPriceAtChange }, 0.0)
            assertTrue(records.all { it.itemId == null && it.itemName == "Milk" })
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(records, db.groceryDao().getPurchaseHistory().first())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun activeScopeSeparatesPrivatePurchasesAndObservesSharedMemberRestocks(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.useTestAccount("alice")
            val manager = SessionManager(db)
            val dao = db.groceryDao()
            dao.insertWithLog(GroceryItem(name = "Alice personal", quantity = 1, price = 1.0, category = "Pantry"))
            val home = manager.createHousehold("Shared home")
            dao.insertWithLog(GroceryItem(name = "Shared rice", quantity = 2, price = 5.0, category = "Pantry"))
            db.useTestAccount("bob")
            assertTrue(dao.getPurchaseHistory().first().isEmpty())
            dao.insertWithLog(GroceryItem(name = "Bob personal", quantity = 1, price = 3.0, category = "Personal Care"))
            manager.joinHousehold(home.joinCode)
            assertEquals(listOf("Shared rice"), dao.getPurchaseHistory().first().map { it.itemName })
            val emissions = Channel<List<InventoryLog>>(Channel.UNLIMITED)
            val collector = launch { dao.getPurchaseHistory().collect { emissions.send(it) } }
            try {
                withTimeout(5000) { assertEquals(1, emissions.receive().size) }
                dao.insertWithLog(GroceryItem(name = "Shared eggs", quantity = 1, price = 2.0, category = "Dairy"))
                withTimeout(5000) { while (emissions.receive().size != 2) { } }
                assertEquals(12.0, dao.getPurchaseHistory().first().sumOf { it.amountChanged * it.unitPriceAtChange }, 0.0)
                manager.selectScope(false)
                assertEquals(listOf("Bob personal"), dao.getPurchaseHistory().first().map { it.itemName })
                db.accountDao().setSession(LocalSessionEntity(userId = "alice"))
                assertEquals(listOf("Alice personal"), dao.getPurchaseHistory().first().map { it.itemName })
                manager.signOut()
                assertTrue(dao.getPurchaseHistory().first().isEmpty())
            } finally { collector.cancelAndJoin(); emissions.close() }
        } finally { db.close() }
    }
}

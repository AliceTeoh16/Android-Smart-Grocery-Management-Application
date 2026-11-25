package com.xmum.smartgrocery.data.local

import android.graphics.Color
import android.os.Looper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity
import com.xmum.smartgrocery.data.local.entity.UserEntity
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.ui.shopping.HouseholdInvitationDialog
import com.xmum.smartgrocery.utils.AndroidCoroutines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** RAM-only regression checks: presentation scheduling, query fan-out and privacy. */
class PerformanceRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun account(db: AppDatabase, id: String = "test-user") {
        db.accountDao().insertUser(UserEntity(id, "$id@example.test", "Test user", "", "", 600000))
        db.accountDao().setSession(LocalSessionEntity(userId = id))
    }

    @Test fun presentationRunsOffMainAndReturnsToTheOriginalCollector(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val result = AndroidCoroutines.background(flow {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                emit((1..10_000).sum())
            }).first()
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(50_005_000, result)
        }
    }

    @Test fun homeActivityUsesOneQueryAndMatchesTheFormerPerItemResults(): Unit = runBlocking {
        val reads = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT * FROM inventory_logs")) reads.incrementAndGet()
            }, Executor { it.run() }).build()
        try {
            account(db)
            val dao = db.groceryDao()
            repeat(25) { i ->
                dao.insertWithLog(GroceryItem(name = "Food $i", quantity = 2, price = 1.0,
                    category = "Produce", expiryDate = null))
            }
            val items = dao.getAllItems().first()
            reads.set(0)
            val previous = items.flatMap { dao.getLogsForItem(it.id).first() }
            val previousReads = reads.getAndSet(0)
            val current = dao.getInventoryActivityLogs().first()
            assertEquals(previous.toSet(), current.toSet())
            assertEquals(25, previousReads)
            assertEquals(1, reads.get())
            assertEquals(25, current.size)
        } finally { db.close() }
    }

    @Test fun optimizedActivityAndStableSessionFlowsRespectAccountAndHouseholdSwitches(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            account(db, "owner")
            val manager = SessionManager(db)
            assertSame(manager.sessions, manager.sessions)
            assertSame(manager.context, manager.context)
            val dao = db.groceryDao()
            dao.insertWithLog(GroceryItem(name = "Private food", quantity = 2, price = 1.0,
                category = "Produce", expiryDate = null))
            manager.createHousehold("Shared pantry")
            dao.insertWithLog(GroceryItem(name = "Shared food", quantity = 2, price = 1.0,
                category = "Produce", expiryDate = null))
            assertEquals(listOf("Shared food"), dao.getInventoryActivityLogs().first().map { it.itemName })
            manager.selectHousehold(null)
            assertEquals(listOf("Private food"), dao.getInventoryActivityLogs().first().map { it.itemName })
            val original = dao.getAllItems().first().single()
            dao.deleteWithoutResult(original)
            assertTrue(dao.getInventoryActivityLogs().first().isEmpty())
            assertTrue(dao.getPurchaseHistory().first().isNotEmpty()) // Historical purchases still survive deletion.
            account(db, "other-user")
            assertTrue(dao.getInventoryActivityLogs().first().isEmpty())
            assertEquals("other-user", manager.sessions.first()!!.userId)
        } finally { db.close() }
    }

    @Test fun bulkQrPixelsKeepTheOriginalInvitationLinkReadable() {
        val link = "smartgrocery://join?code=482913&name=共享厨房"
        val size = 320
        val bitmap = HouseholdInvitationDialog.createQrBitmap(link, size)
        try {
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            assertTrue(pixels.any { it == Color.BLACK })
            assertTrue(pixels.any { it == Color.WHITE })
            val source = RGBLuminanceSource(size, size, pixels)
            assertEquals(link, MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        } finally { bitmap.recycle() }
    }
}

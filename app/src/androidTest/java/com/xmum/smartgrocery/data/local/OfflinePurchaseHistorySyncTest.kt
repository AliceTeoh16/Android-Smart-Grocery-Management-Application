package com.xmum.smartgrocery.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import com.xmum.smartgrocery.data.sharing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real production HTTP sync through an isolated test proxy with controllable connection faults. */
class OfflinePurchaseHistorySyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = "http://10.0.2.2:8791/"
    private val cipher = KeystoreTokenCipher()
    private val controls = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private fun service(db: AppDatabase) = SharingService(db, { url }, cipher)
    private fun peer() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private fun email(label: String) = "$label-${UUID.randomUUID()}@example.test"
    private fun faults(offline: Boolean = false, dropReplies: Boolean = false) {
        val body = """{"offline":$offline,"dropSyncReplies":$dropReplies}"""
        val request = Request.Builder().url(url + "__test/control")
            .post(RequestBody.create(MediaType.parse("application/json"), body)).build()
        controls.newCall(request).execute().use {
            check(it.isSuccessful) { "The isolated fault-injection server is required on port 8791." }
            check(JsonParser.parseString(it.body()!!.string()).asJsonObject["service"].asString == "offline-history-test-proxy")
        }
    }
    private fun faultCount(key: String): Int {
        controls.newCall(Request.Builder().url(url + "__test/state").build()).execute().use {
            check(it.isSuccessful)
            return JsonParser.parseString(it.body()!!.string()).asJsonObject[key].asInt
        }
    }
    private suspend fun history(db: AppDatabase) = db.groceryDao().getPurchaseHistory().first()
    private fun total(records: List<InventoryLog>) = records.sumOf { it.amountChanged * it.unitPriceAtChange }
    private fun signatures(records: List<InventoryLog>) = records.map {
        listOf(it.itemName, it.amountChanged.toString(), it.unitPriceAtChange.toString(), it.timestamp.toString())
    }.groupingBy { it }.eachCount()
    private suspend fun assertHistory(a: AppDatabase, b: AppDatabase, count: Int, expectedTotal: Double) {
        val left = history(a); val right = history(b)
        assertEquals(count, left.size); assertEquals(count, right.size)
        assertEquals(expectedTotal, total(left), 0.0); assertEquals(expectedTotal, total(right), 0.0)
        assertEquals(signatures(left), signatures(right))
    }
    private suspend fun remote(db: AppDatabase): RemoteSnapshot {
        val user = db.accountDao().session()!!.userId!!
        val auth = db.sharingDao().account(user, url)!!
        return SharingApi(url).homes(cipher.decrypt(auth.encryptedToken)).households.single()
    }
    private suspend fun assertRemote(db: AppDatabase, count: Int, expectedTotal: Double) {
        val purchases = remote(db).records.filter { it.collection == "logs" && it.data["changeType"].asString == "ADD" }
        assertEquals(count, purchases.size)
        assertEquals(count, purchases.map { it.id }.toSet().size)
        assertEquals(expectedTotal, purchases.sumOf { it.data["amountChanged"].asInt * it.data["unitPriceAtChange"].asDouble }, 0.0)
    }

    @Test fun purchasesAddedOfflineSurviveRestartAndAutomaticallySyncWhenConnectionReturns(): Unit = runBlocking {
        val name = "offline-purchase-history-${UUID.randomUUID()}.db"
        var a = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val b = peer()
        val preferences = context.getSharedPreferences("household_sharing", android.content.Context.MODE_PRIVATE)
        val previousServer = preferences.getString("server_url", null)
        val previouslyDisabled = SharingRuntime.disabledForTests
        try {
            faults()
            var sa = service(a); val sb = service(b)
            assertNull(sa.register("Offline buyer", email("offline-buyer"), "correct123"))
            assertNull(sb.register("Household peer", email("offline-peer"), "correct123"))
            val home = sa.createHousehold("Offline purchase home", "")
            val rice = a.groceryDao().insertWithLog(GroceryItem(name = "Initial rice", quantity = 1, price = 2.0,
                category = "Pantry", threshold = 0)).toInt()
            sa.synchronize(); sb.joinHousehold(home.joinCode)
            assertHistory(a, b, 1, 2.0)

            val refusedBefore = faultCount("refusedRequests")
            faults(offline = true)
            a.groceryDao().insertWithLog(GroceryItem(name = "Offline milk", quantity = 2, price = 4.0, category = "Dairy", threshold = 0))
            a.groceryDao().insertWithLog(GroceryItem(name = "Offline bread", quantity = 1, price = 3.0, category = "Bakery", threshold = 0))
            a.groceryDao().updateQuantityWithLog(rice, 3)
            val error = runCatching { sa.synchronize() }.exceptionOrNull()
            assertTrue(error is IOException && error !is SharingException)
            assertTrue(faultCount("refusedRequests") > refusedBefore)
            assertEquals(4, history(a).size); assertEquals(17.0, total(history(a)), 0.0)
            assertEquals(1, history(b).size) // The other device only has its last successful snapshot.
            assertEquals(3, a.sharingDao().pending().first().count { it.type == "logs" })
            val deviceId = a.sharingDao().deviceId()
            a.close()
            a = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(deviceId, a.sharingDao().deviceId())
            assertEquals(4, history(a).size)
            assertEquals(3, a.sharingDao().pending().first().count { it.type == "logs" })

            // Exercise the real app polling/debounce runtime, without tapping Sync now.
            SharingRuntime.disabledForTests = false
            val resumed = SharingRuntime.install(context, a)!!
            resumed.configureServer(url)
            faults()
            withTimeout(20_000) { a.sharingDao().pending().first { it.isEmpty() } }
            sa = resumed.service
            sb.synchronize()
            assertHistory(a, b, 4, 17.0)
            repeat(3) { sa.synchronize(); sb.synchronize() }
            assertHistory(a, b, 4, 17.0)
            assertRemote(a, 4, 17.0)
            assertEquals(3, b.groceryDao().getAllItemsOnce().single { it.name == "Initial rice" }.quantity)
            for (db in listOf(a, b)) {
                assertTrue(db.sharingDao().pending().first().isEmpty())
                assertTrue(db.sharingDao().outbox(home.householdId).isEmpty())
            }
        } finally {
            SharingRuntime.uninstallForTests(a)
            SharingRuntime.disabledForTests = previouslyDisabled
            if (previousServer == null) preferences.edit().remove("server_url").commit()
            else preferences.edit().putString("server_url", previousServer).commit()
            runCatching { faults() }; a.close(); b.close(); context.deleteDatabase(name)
        }
    }

    @Test fun acceptedUploadWithLostAcknowledgementRetriesAfterRestartWithoutDuplicatePurchases(): Unit = runBlocking {
        val name = "lost-purchase-ack-${UUID.randomUUID()}.db"
        var a = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val b = peer()
        try {
            faults()
            var sa = service(a); val sb = service(b)
            assertNull(sa.register("Retry buyer", email("ack-buyer"), "correct123"))
            assertNull(sb.register("Retry peer", email("ack-peer"), "correct123"))
            val home = sa.createHousehold("Lost acknowledgement home", "")
            val rice = a.groceryDao().insertWithLog(GroceryItem(name = "Retry rice", quantity = 2, price = 5.0,
                category = "Pantry", threshold = 0)).toInt()
            sa.synchronize(); sb.joinHousehold(home.joinCode)

            faults(offline = true)
            a.groceryDao().updateQuantityWithLog(rice, 3)
            a.groceryDao().insertWithLog(GroceryItem(name = "Offline eggs", quantity = 2, price = 3.0, category = "Dairy", threshold = 0))
            assertTrue(runCatching { sa.synchronize() }.exceptionOrNull() is IOException)
            assertEquals(3, history(a).size); assertEquals(21.0, total(history(a)), 0.0)

            val lostBefore = faultCount("committedRepliesDropped")
            faults(dropReplies = true)
            val failedAck = runCatching { sa.synchronize() }.exceptionOrNull()
            assertTrue(failedAck is IOException && failedAck !is SharingException)
            assertTrue(faultCount("committedRepliesDropped") > lostBefore)
            val inFlight = a.sharingDao().outbox(home.householdId)
            assertTrue(inFlight.isNotEmpty())
            assertEquals(2, inFlight.count { JsonParser.parseString(it.mutationJson).asJsonObject["collection"].asString == "logs" })
            assertRemote(a, 3, 21.0) // The server committed the upload despite the missing response.

            a.groceryDao().updateQuantityWithLog(rice, 4)
            a.groceryDao().insertWithLog(GroceryItem(name = "After-timeout milk", quantity = 1, price = 4.0, category = "Dairy", threshold = 0))
            assertEquals(5, history(a).size); assertEquals(30.0, total(history(a)), 0.0)
            assertEquals(inFlight, a.sharingDao().outbox(home.householdId))
            a.close()
            a = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(inFlight, a.sharingDao().outbox(home.householdId))
            assertEquals(5, history(a).size)

            faults()
            sa = service(a)
            repeat(4) { sa.synchronize(); sb.synchronize() }
            assertHistory(a, b, 5, 30.0)
            assertRemote(a, 5, 30.0)
            assertEquals(4, remote(a).records.single { it.collection == "inventory" && !it.deleted && it.data["name"].asString == "Retry rice" }.data["quantity"].asInt)
            for (db in listOf(a, b)) {
                assertTrue(db.sharingDao().outbox(home.householdId).isEmpty())
                assertTrue(db.sharingDao().pending().first().isEmpty())
            }
        } finally { runCatching { faults() }; a.close(); b.close(); context.deleteDatabase(name) }
    }
}

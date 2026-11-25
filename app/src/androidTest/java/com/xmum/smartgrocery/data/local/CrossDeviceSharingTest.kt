package com.xmum.smartgrocery.data.local

import com.xmum.smartgrocery.data.local.dao.getBudgetForMonthSync
import com.xmum.smartgrocery.data.local.dao.setBudget
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.repository.HouseholdRepository
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.data.sharing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Each Room database represents a different device, communicating through the real HTTP server. */
class CrossDeviceSharingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = "http://10.0.2.2:8791/"
    private val cipher = KeystoreTokenCipher()
    private fun device() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private fun sharing(db: AppDatabase) = SharingService(db, { url }, cipher)
    private fun email(prefix: String) = "$prefix-${UUID.randomUUID()}@example.test"
    private suspend fun account(db: AppDatabase) = db.sharingDao().account(db.accountDao().session()!!.userId!!, url)!!

    @Test fun sharedAuthorNamesSyncOfflineEditsAndRemainStableOnRepeatedSync(): Unit = runBlocking {
        val a = device(); val b = device()
        try {
            val sa = sharing(a); val sb = sharing(b)
            assertNull(sa.register("Alice", email("author-alice"), "correct123"))
            assertNull(sb.register("Ben", email("author-ben"), "correct123"))
            a.groceryDao().insertWithLog(GroceryItem(name = "Alice private", quantity = 1, price = 1.0, category = "Dairy"))
            assertTrue(a.sharingDao().pending().first().isEmpty())
            val home = sa.createHousehold("Author family", "")
            a.groceryDao().insertWithLog(GroceryItem(name = "Shared milk", quantity = 3, price = 2.0, category = "Dairy",
                unit = "bottles", storageLocation = "Fridge"))
            a.shoppingListDao().insertManual(ShoppingListItem(itemName = "Shared eggs", category = "Dairy", quantityNeeded = 2,
                estimatedUnitPrice = 3.0, addedBy = "Manual", assignedTo = "Household"))
            sa.synchronize(); sb.joinHousehold(home.joinCode)
            val inventory = b.groceryDao().getAllItemsOnce().single()
            assertEquals("bottles", inventory.unit); assertEquals("Fridge", inventory.storageLocation)
            assertEquals("bottles", b.groceryDao().getPurchaseHistory().first().single().unit)
            val shopping = b.shoppingListDao().getShoppingListItems().first().single()
            assertEquals("Added by Alice", inventory.sharedChangeIndicator)
            assertEquals("Added by Alice", shopping.sharedChangeIndicator)
            // These writes are local only until the explicit sync below.
            b.groceryDao().updateQuantity(inventory.id, 4)
            b.shoppingListDao().setChecked(shopping.id, true)
            assertEquals("Updated by Ben", b.groceryDao().getItemById(inventory.id)!!.sharedChangeIndicator)
            assertEquals("Checked by Ben", b.shoppingListDao().getById(shopping.id)!!.sharedChangeIndicator)
            assertEquals("Added by Alice", a.groceryDao().getAllItemsOnce().single().sharedChangeIndicator)
            sb.synchronize(); sa.synchronize()
            val first = a.groceryDao().getAllItemsOnce().single()
            val checked = a.shoppingListDao().getShoppingListItems().first().single()
            assertEquals("Alice", first.addedByName); assertEquals("Updated by Ben", first.sharedChangeIndicator)
            assertEquals("Alice", checked.addedByName); assertEquals("Checked by Ben", checked.sharedChangeIndicator)
            assertEquals("Manual", checked.addedBy)
            repeat(3) { sa.synchronize(); sb.synchronize() }
            assertEquals(first, a.groceryDao().getAllItemsOnce().single())
            assertEquals(checked, a.shoppingListDao().getShoppingListItems().first().single())
            assertTrue(a.sharingDao().pending().first().isEmpty()); assertTrue(b.sharingDao().pending().first().isEmpty())
            b.shoppingListDao().setChecked(shopping.id, false)
            sb.synchronize(); sa.synchronize()
            assertEquals("Unchecked by Ben", a.shoppingListDao().getShoppingListItems().first().single().sharedChangeIndicator)
            assertFalse(b.groceryDao().getAllItemsOnce().any { it.name == "Alice private" })
            SessionManager(a).selectScope(false)
            assertNull(a.groceryDao().getAllItemsOnce().single().sharedChangeIndicator)
        } finally { a.close(); b.close() }
    }

    @Test fun householdPurchaseHistorySyncsBothWaysAndPersonalHistoryNeverLeavesItsDevice(): Unit = runBlocking {
        val a = device(); val b = device(); val outsider = device()
        try {
            val sa = sharing(a); val sb = sharing(b)
            assertNull(sa.register("History Alice", email("history-alice"), "correct123"))
            assertNull(sb.register("History Bob", email("history-bob"), "correct123"))
            val privateA = a.groceryDao().insertWithLog(GroceryItem(name = "Alice private purchase", quantity = 2,
                price = 9.0, category = "Personal Care", threshold = 0)).toInt()
            b.groceryDao().insertWithLog(GroceryItem(name = "Bob private purchase", quantity = 1,
                price = 4.0, category = "Personal Care", threshold = 0))
            b.groceryDao().insertWithLog(GroceryItem(name = "Bob private placeholder", quantity = 0,
                price = 1.0, category = "Pantry", threshold = 0))
            assertTrue(a.sharingDao().pending().first().isEmpty())
            assertTrue(b.sharingDao().pending().first().isEmpty())

            val homeA = sa.createHousehold("Purchase history family", "Shared history only")
            val riceA = a.groceryDao().insertWithLog(GroceryItem(name = "Shared rice purchase", quantity = 2,
                price = 5.0, category = "Pantry", threshold = 0)).toInt()
            sa.synchronize()
            sb.joinHousehold(homeA.joinCode)
            val riceB = b.groceryDao().getAllItemsOnce().single().id
            assertNotEquals(riceA, riceB) // History must not depend on matching device-local item IDs.
            val firstA = a.groceryDao().getPurchaseHistory().first().single()
            val firstB = b.groceryDao().getPurchaseHistory().first().single()
            assertEquals("Shared rice purchase", firstB.itemName)
            assertEquals(firstA.timestamp, firstB.timestamp)
            assertEquals(2, firstB.amountChanged)
            assertEquals(5.0, firstB.unitPriceAtChange, 0.0)

            b.groceryDao().insertWithLog(GroceryItem(name = "Shared eggs purchase", quantity = 3,
                price = 4.0, category = "Dairy", threshold = 0))
            sb.synchronize(); sa.synchronize()
            for (db in listOf(a, b)) {
                val history = db.groceryDao().getPurchaseHistory().first()
                assertEquals(2, history.size)
                assertEquals(22.0, history.sumOf { it.amountChanged * it.unitPriceAtChange }, 0.0)
            }

            // Local restocks remain visible before syncing; old prices and deleted stock stay in history.
            b.groceryDao().update(b.groceryDao().getItemById(riceB)!!.copy(price = 6.0))
            b.groceryDao().updateQuantityWithLog(riceB, 3)
            b.groceryDao().delete(b.groceryDao().getItemById(riceB)!!)
            assertEquals(3, b.groceryDao().getPurchaseHistory().first().size)
            assertTrue(b.sharingDao().pending().first().any { it.type == "logs" })
            sb.synchronize(); sa.synchronize(); sb.synchronize(); sa.synchronize()
            val historyA = a.groceryDao().getPurchaseHistory().first()
            val historyB = b.groceryDao().getPurchaseHistory().first()
            fun signatures(logs: List<InventoryLog>) = logs.map {
                listOf(it.itemName, it.amountChanged.toString(), it.unitPriceAtChange.toString(), it.timestamp.toString())
            }.groupingBy { it }.eachCount()
            assertEquals(signatures(historyA), signatures(historyB))
            for (history in listOf(historyA, historyB)) {
                assertEquals(3, history.size) // Repeated syncs cannot duplicate purchases.
                assertEquals(28.0, history.sumOf { it.amountChanged * it.unitPriceAtChange }, 0.0)
                assertEquals(setOf(5.0, 6.0), history.filter { it.itemName == "Shared rice purchase" }.map { it.unitPriceAtChange }.toSet())
                assertTrue(history.filter { it.itemName == "Shared rice purchase" }.all { it.itemId == null })
                assertFalse(history.any { it.itemName.contains("private") })
            }

            SessionManager(a).selectScope(false)
            SessionManager(b).selectScope(false)
            a.groceryDao().updateQuantityWithLog(privateA, 3)
            sa.synchronize(); sb.synchronize() // Personal view does not disable household sync or upload personal logs.
            assertEquals(setOf("Alice private purchase"), a.groceryDao().getPurchaseHistory().first().map { it.itemName }.toSet())
            assertEquals(27.0, a.groceryDao().getPurchaseHistory().first().sumOf { it.amountChanged * it.unitPriceAtChange }, 0.0)
            assertEquals(listOf("Bob private purchase"), b.groceryDao().getPurchaseHistory().first().map { it.itemName })
            assertEquals(4.0, b.groceryDao().getPurchaseHistory().first().single().unitPriceAtChange, 0.0)
            for ((db, forbiddenName) in listOf(a to "Bob private purchase", b to "Alice private purchase")) {
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM inventory_logs WHERE itemName = ?", arrayOf(forbiddenName)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                assertTrue(db.sharingDao().pending().first().isEmpty())
            }
            val token = cipher.decrypt(account(a).encryptedToken)
            val remote = SharingApi(url).homes(token).households.single()
            val uploadedHistory = remote.records.filter { it.collection == "logs" }
            assertEquals(3, uploadedHistory.size)
            assertEquals(setOf("Shared rice purchase", "Shared eggs purchase"), uploadedHistory.map { it.data["itemName"].asString }.toSet())
            assertFalse(remote.records.any { it.data.toString().contains("private") })

            assertNull(sharing(outsider).register("History outsider", email("history-outsider"), "correct123"))
            val outsiderToken = cipher.decrypt(account(outsider).encryptedToken)
            assertTrue(SharingApi(url).homes(outsiderToken).households.isEmpty())
            val error = runCatching { SharingApi(url).sync(outsiderToken, remote.household.id, emptyList()) }.exceptionOrNull()
            assertTrue(error is SharingException && error.status == 403)
            assertTrue(outsider.groceryDao().getPurchaseHistory().first().isEmpty())
        } finally { a.close(); b.close(); outsider.close() }
    }

    @Test fun independentDevicesJoinAndSyncPantryShoppingBudgetsSettingsWithoutUploadingPersonalRows(): Unit = runBlocking {
        val a = device(); val b = device()
        try {
            val sa = sharing(a); val sb = sharing(b)
            assertNull(sa.register("Alice", email("alice"), "correct123"))
            assertNull(sb.register("Bob", email("bob"), "correct123"))
            a.groceryDao().insertWithLog(GroceryItem(name = "Alice private stock", quantity = 1, price = 3.0, category = "Pantry", threshold = 0))
            a.shoppingListDao().insertManual(ShoppingListItem(itemName = "Alice private plan", category = "Personal Care", quantityNeeded = 1, estimatedUnitPrice = 2.0, assignedTo = "Me"))
            a.budgetDao().setBudget(BudgetEntry("2026-11", 50.0))
            repeat(4) { b.groceryDao().insertWithLog(GroceryItem(name = "Bob private $it", quantity = 1, price = 7.0, category = "Pantry", threshold = 0)) }
            b.budgetDao().setBudget(BudgetEntry("2026-11", 30.0))
            assertTrue(a.sharingDao().pending().first().isEmpty())
            val home = sa.createHousehold("Family", "Devices sharing one pantry")
            val stockA = a.groceryDao().insertWithLog(GroceryItem(name = "Shared eggs", quantity = 10, price = 2.0, category = "Dairy", threshold = 0)).toInt()
            a.shoppingListDao().insertManual(ShoppingListItem(sourceItemId = stockA, itemName = "Shared milk", category = "Dairy", quantityNeeded = 2, estimatedUnitPrice = 3.0))
            a.budgetDao().setBudget(BudgetEntry("2026-11", 500.0))
            HouseholdRepository(a).adjustWarningDays(home.householdId, 1)
            sa.synchronize()
            val joined = sb.joinHousehold(home.joinCode)
            val stockB = b.groceryDao().getAllItemsOnce().single()
            assertNotEquals(stockA, stockB.id) // Device-local Int IDs must be mapped, never copied.
            assertEquals("Shared eggs", stockB.name)
            assertEquals(stockB.id, b.shoppingListDao().getShoppingListItems().first().single().sourceItemId)
            assertEquals(500.0, b.budgetDao().getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(20.0, b.budgetDao().getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
            assertEquals(4, b.householdDao().observeSettings().first()!!.expiryWarningDays)
            assertTrue(b.sharingDao().pending().first().isEmpty()) // Remote imports never echo into the outbox.

            // Both devices edit from the same baseline before either pulls the other's change.
            a.groceryDao().updateQuantityWithLog(stockA, 12)
            b.groceryDao().updateQuantityWithLog(stockB.id, 9)
            sb.synchronize(); sa.synchronize(); sb.synchronize()
            assertEquals(11, a.groceryDao().getItemById(stockA)!!.quantity)
            assertEquals(11, b.groceryDao().getItemById(stockB.id)!!.quantity)
            b.budgetDao().setBudget(BudgetEntry("2026-11", 650.0))
            sb.synchronize(); sa.synchronize()
            assertEquals(650.0, a.budgetDao().getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(24.0, b.budgetDao().getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
            val remote = SharingApi(url).homes(cipher.decrypt(account(a).encryptedToken)).households.single()
            assertFalse(remote.records.any { it.data.toString().contains("private", ignoreCase = true) })
            assertTrue(remote.records.all { !it.data.has("userId") && !it.data.has("householdId") && !it.data.has("scopeKey") })
            assertTrue(remote.records.filter { it.collection == "budget" }.all { it.data["limitAmount"].asDouble == 650.0 })
            SessionManager(b).selectScope(false)
            assertEquals(4, b.groceryDao().getAllItemsOnce().size)
            assertEquals(30.0, b.budgetDao().getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(28.0, b.budgetDao().getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
            sb.synchronize() // Sync also works while the user is viewing Personal.
            SessionManager(b).selectHousehold(joined.householdId)
            a.groceryDao().delete(a.groceryDao().getItemById(stockA)!!)
            sa.synchronize(); sb.synchronize()
            assertTrue(b.groceryDao().getAllItemsOnce().isEmpty())
            assertEquals(24.0, b.budgetDao().getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
            SessionManager(a).selectScope(false)
            assertEquals(50.0, a.budgetDao().getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(1, a.groceryDao().getAllItemsOnce().size)
            for (db in listOf(a, b)) db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { a.close(); b.close() }
    }

    @Test fun acceptedButUnacknowledgedMutationSurvivesRestartAndLaterEditsWithoutDoubleAddingStock(): Unit = runBlocking {
        val name = "sharing-retry-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            var service = sharing(db)
            assertNull(service.register("Retry device", email("retry"), "correct123"))
            val home = service.createHousehold("Retry home", "")
            val id = db.groceryDao().insertWithLog(GroceryItem(name = "Stock", quantity = 5, price = 1.0, category = "Pantry", threshold = 0)).toInt()
            service.synchronize()
            db.groceryDao().updateQuantityWithLog(id, 7)
            val mark = db.sharingDao().marks(home.householdId).single { it.type == "inventory" && it.localKey == id.toString() }
            val data = JsonParser.parseString(mark.baseline).asJsonObject.apply { addProperty("quantity", 7) }
            val patch = com.google.gson.JsonObject().apply { addProperty("quantity", 7) }
            val mutation = RemoteMutation("${db.sharingDao().deviceId()}:${mark.remoteId}:${mark.revision}", "inventory", mark.remoteId, mark.version, false, patch, 2)
            val gson = GsonBuilder().serializeNulls().create()
            db.sharingDao().enqueue(PendingOperation(mutation.mutationId, home.householdId, gson.toJson(mark), data.toString(), gson.toJson(mutation)))
            val auth = account(db)
            val link = db.sharingDao().household(home.householdId)!!
            SharingApi(url).sync(cipher.decrypt(auth.encryptedToken), link.remoteHouseholdId, listOf(mutation))
            // The response is lost. Another edit arrives before the process restarts.
            db.groceryDao().updateQuantityWithLog(id, 8)
            val deviceId = db.sharingDao().deviceId()
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(deviceId, db.sharingDao().deviceId())
            assertEquals(1, db.sharingDao().outbox(home.householdId).size)
            service = sharing(db)
            service.synchronize(); service.synchronize()
            assertEquals(8, db.groceryDao().getItemById(id)!!.quantity)
            val remote = SharingApi(url).homes(cipher.decrypt(account(db).encryptedToken)).households.single()
            assertEquals(8, remote.records.single { it.collection == "inventory" && !it.deleted }.data["quantity"].asInt)
            assertTrue(db.sharingDao().outbox(home.householdId).isEmpty())
            assertTrue(db.sharingDao().pending().first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun remoteAccountCannotClaimDifferentLocalCredentialsOrReadAnotherHousehold(): Unit = runBlocking {
        val a = device(); val stranger = device()
        try {
            val target = email("private")
            val local = SessionManager(a)
            assertNull(local.register("Private user", target, "local123"))
            val oldId = local.currentUserId()!!
            val privateId = a.groceryDao().insertWithLog(GroceryItem(name = "Private secret", quantity = 2, price = 1.0, category = "Pantry")).toInt()
            local.signOut()
            SharingApi(url).register("Other identity", target, "remote123")
            assertNotNull(sharing(a).login(target, "remote123"))
            assertNull(local.currentUserId())
            assertNull(a.sharingDao().account(oldId, url))
            assertEquals("Private secret", a.sharingDao().localUser(oldId)?.let {
                a.openHelper.readableDatabase.query("SELECT name FROM grocery_items WHERE id=$privateId").use { c -> c.moveToFirst(); c.getString(0) }
            })
            val ownerEmail = email("owner")
            assertNull(sharing(a).register("Owner", ownerEmail, "correct123"))
            val home = sharing(a).createHousehold("Private household", "")
            val outsider = sharing(stranger)
            assertNull(outsider.register("Stranger", email("stranger"), "correct123"))
            val forbidden = runCatching { SharingApi(url).sync(cipher.decrypt(account(stranger).encryptedToken),
                a.sharingDao().household(home.householdId)!!.remoteHouseholdId, emptyList()) }.exceptionOrNull()
            assertTrue(forbidden is SharingException && forbidden.status == 403)
            assertTrue(stranger.groceryDao().getAllItemsOnce().isEmpty())
        } finally { a.close(); stranger.close() }
    }

    @Test fun version11MigrationKeepsPrivateDataOutOfTheSyncQueue(): Unit = runBlocking {
        val name = "sharing-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        var db: AppDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("database/schema-v11.sql").bufferedReader().use { it.readText() }
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach(old::execSQL)
                old.execSQL("INSERT INTO users VALUES ('u','u@example.test','User','','',600000,1)")
                old.execSQL("INSERT INTO local_session VALUES (1,'u',NULL,NULL)")
                old.execSQL("INSERT INTO grocery_items VALUES (7,'Private',2,3.0,'Food & Beverage',NULL,0,1000,'Pantry','u',NULL)")
                old.version = 11
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14).build(); db = migrated
            assertEquals(14, migrated.openHelper.writableDatabase.version)
            assertEquals("Private", migrated.groceryDao().getItemById(7)!!.name)
            migrated.groceryDao().updateQuantityWithLog(7, 3)
            assertTrue(migrated.sharingDao().pending().first().isEmpty())
            assertTrue(migrated.sharingDao().households(url).isEmpty())
            migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}

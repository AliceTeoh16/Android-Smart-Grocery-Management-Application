package com.xmum.smartgrocery.data.local

import com.xmum.smartgrocery.data.local.dao.getBudgetForMonthSync
import com.xmum.smartgrocery.data.local.dao.setBudget
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real SQLite tests: authorization, transactions, Room invalidation and migration validation. */
class AccountScopeStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private suspend fun account(db: AppDatabase, id: String) {
        db.accountDao().insertUser(UserEntity(id, "$id@example.com", id, "", "", 600000))
        db.accountDao().setSession(LocalSessionEntity(userId = id))
    }
    private fun grocery(name: String = "Eggs") = GroceryItem(name = name, quantity = 2, price = 3.0, category = "Dairy", threshold = 3)
    private fun shopping() = ShoppingListItem(itemName = "Eggs", category = "Dairy", quantityNeeded = 1, estimatedUnitPrice = 3.0)
    private fun history(id: String) = ChecklistHistoryItem(id, "Dinner", 2, "pending")

    @Test fun accountsCannotReadUpdateDeleteOrReplaceEachOthersData(): Unit = runBlocking {
        val db = database()
        try {
            account(db, "alice")
            val inventory = db.groceryDao(); val shopping = db.shoppingListDao(); val budgets = db.budgetDao(); val history = db.checklistHistoryDao()
            val aliceId = inventory.insertWithLog(grocery()).toInt()
            val aliceItem = inventory.getItemById(aliceId)!!
            val aliceShoppingId = shopping.insertManual(shopping()).toInt()
            val aliceShopping = shopping.getById(aliceShoppingId)!!
            history.saveWithPruning(history("alice-history"))
            budgets.setBudget(BudgetEntry("2026-11", 100.0))

            account(db, "bob")
            assertTrue(inventory.getAllItemsOnce().isEmpty())
            assertNull(inventory.getItemByName("Eggs"))
            assertNull(inventory.findBestMatchingItem("Eggs"))
            assertTrue(inventory.findMatchingItems("Eggs").isEmpty())
            assertTrue(inventory.getLogsForItem(aliceId).first().isEmpty())
            assertTrue(shopping.getShoppingListItems().first().isEmpty())
            assertNull(budgets.getBudgetForMonthSync("2026-11"))
            assertNull(history.getChecklistHistoryById("alice-history"))
            assertEquals(0, inventory.update(aliceItem.copy(quantity = 999)))
            assertEquals(0, inventory.delete(aliceItem))
            inventory.updateQuantityWithLog(aliceId, 999)
            inventory.deductQuantity(aliceId, 999)
            shopping.setChecked(aliceShoppingId, true)
            assertEquals(0, shopping.update(aliceShopping.copy(quantityNeeded = 999)))
            assertEquals(0, shopping.delete(aliceShopping))
            history.updateStatus("alice-history", "cooked")
            history.clearAll()
            assertTrue(runCatching { history.insert(history("alice-history")) }.isFailure)
            assertTrue(runCatching { inventory.insert(grocery().copy(userId = "alice")) }.isFailure)
            val bobId = inventory.insertWithLog(grocery()).toInt()
            budgets.setBudget(BudgetEntry("2026-11", 200.0))
            repeat(12) { history.saveWithPruning(history("bob-$it")) }
            assertEquals(10, history.getCount())
            inventory.delete(inventory.getItemById(bobId)!!)
            assertEquals(6.0, budgets.getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)

            db.accountDao().setSession(LocalSessionEntity(userId = "alice"))
            assertEquals(2, inventory.getItemById(aliceId)!!.quantity)
            assertFalse(shopping.getById(aliceShoppingId)!!.isChecked)
            assertEquals("pending", history.getChecklistHistoryById("alice-history")!!.status)
            assertEquals(1, history.getCount())
            assertEquals(100.0, budgets.getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(6.0, budgets.getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
        } finally { db.close() }
    }

    @Test fun membersSharePantryShoppingAndOneBudgetButPersonalDataStaysPrivate(): Unit = runBlocking {
        val db = database()
        try {
            account(db, "alice")
            val manager = SessionManager(db)
            val personalId = db.groceryDao().insertWithLog(grocery("Private stock")).toInt()
            db.budgetDao().setBudget(BudgetEntry("2026-11", 50.0))
            val home = manager.createHousehold("Our home")
            val sharedId = db.groceryDao().insertWithLog(grocery("Shared stock")).toInt()
            val shoppingId = db.shoppingListDao().insertManual(shopping()).toInt()
            db.budgetDao().setBudget(BudgetEntry("2026-11", 500.0))
            account(db, "bob")
            assertTrue(runCatching { manager.selectHousehold(home.householdId) }.isFailure)
            assertTrue(runCatching { manager.joinHousehold("000000") }.isFailure)
            manager.joinHousehold(home.joinCode)
            assertNull(db.groceryDao().getItemById(personalId))
            assertEquals("alice", db.groceryDao().getItemById(sharedId)!!.userId)
            db.groceryDao().updateQuantityWithLog(sharedId, 4)
            db.shoppingListDao().setChecked(shoppingId, true)
            db.budgetDao().setBudget(BudgetEntry("2026-11", 600.0))
            assertEquals(1, db.groceryDao().getAllItemsOnce().size)
            assertEquals(12.0, db.budgetDao().getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
            assertTrue(runCatching { db.shoppingListDao().insertManual(shopping().copy(assignedTo = "Me")) }.isFailure)
            manager.selectHousehold(null)
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            assertNull(db.budgetDao().getBudgetForMonthSync("2026-11"))
            db.budgetDao().setBudget(BudgetEntry("2026-11", 75.0))

            db.accountDao().setSession(LocalSessionEntity(userId = "alice", householdId = home.householdId))
            assertEquals(4, db.groceryDao().getItemById(sharedId)!!.quantity)
            assertTrue(db.shoppingListDao().getById(shoppingId)!!.isChecked)
            assertEquals(600.0, db.budgetDao().getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            manager.selectHousehold(null)
            assertEquals(50.0, db.budgetDao().getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(listOf(personalId), db.groceryDao().getAllItemsOnce().map { it.id })
            manager.selectHousehold(home.householdId)
            manager.leaveHousehold(home.householdId)
            assertTrue(runCatching { manager.selectHousehold(home.householdId) }.isFailure)
            // A stale/forged session must not bypass membership on either reads or writes.
            db.accountDao().setSession(LocalSessionEntity(userId = "alice", householdId = home.householdId))
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            assertTrue(runCatching { db.groceryDao().insertWithLog(grocery()) }.isFailure)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
    }

    @Test fun logoutAndAccountSwitchInvalidateAnExistingRoomCollectorAndRejectWrites(): Unit = runBlocking {
        val db = database()
        try {
            account(db, "alice")
            db.groceryDao().insertWithLog(grocery())
            val emissions = Channel<List<GroceryItem>>(Channel.UNLIMITED)
            val collector = launch { db.groceryDao().getAllItems().collect { emissions.send(it) } }
            try {
                withTimeout(5000) { assertEquals(1, emissions.receive().size) }
                SessionManager(db).signOut()
                withTimeout(5000) { while (emissions.receive().isNotEmpty()) { } }
                assertTrue(runCatching { db.groceryDao().insertWithLog(grocery()) }.isFailure)
                assertTrue(runCatching { db.shoppingListDao().insertManual(shopping()) }.isFailure)
                assertTrue(runCatching { db.budgetDao().setBudget(BudgetEntry("2026-11", 1.0)) }.isFailure)
                assertTrue(runCatching { db.checklistHistoryDao().insert(history("signed-out")) }.isFailure)
                account(db, "bob")
                db.groceryDao().insertWithLog(grocery("Bob only"))
                withTimeout(5000) { while (emissions.receive().singleOrNull()?.name != "Bob only") { } }
            } finally { collector.cancelAndJoin(); emissions.close() }
        } finally { db.close() }
    }

    @Test fun authenticationChecksCredentialsAndNormalizesAccountIdentity(): Unit = runBlocking {
        val db = database()
        try {
            val manager = SessionManager(db)
            assertNull(manager.register("Alice", " Alice@Example.com ", "correct123"))
            val id = manager.currentUserId()
            val user = manager.currentUser()!!
            assertNotEquals("correct123", user.passwordHash)
            assertTrue(user.passwordSalt.isNotEmpty())
            assertNotNull(manager.register("Duplicate", "ALICE@example.com", "other123"))
            manager.signOut()
            assertEquals("Email or password is incorrect.", manager.signIn("alice@example.com", "wrong123"))
            assertNull(manager.currentUserId())
            assertEquals("No account found with this email. Please sign up to create an account first.",
                manager.signIn("unknown@example.com", "correct123"))
            assertNull(manager.currentUserId())
            assertNull(manager.signIn("ALICE@EXAMPLE.COM", "correct123"))
            assertEquals(id, manager.currentUserId())
        } finally { db.close() }
    }

    @Test fun shoppingTargetsAndTransfersNeverSharePrivateStockImplicitly(): Unit = runBlocking {
        val db = database()
        try {
            account(db, "alice")
            val manager = SessionManager(db)
            val repository = com.xmum.smartgrocery.data.repository.ShoppingListRepository(db.shoppingListDao(), manager)
            val privateStockId = db.groceryDao().insertWithLog(grocery("Private eggs")).toInt()
            val shoppingId = db.shoppingListDao().insertManual(shopping().copy(sourceItemId = privateStockId, assignedTo = "Me")).toInt()
            val privatePlan = db.shoppingListDao().getById(shoppingId)!!
            repository.assignTo(privatePlan, "Household")
            val home = manager.households().single()
            val transferred = db.shoppingListDao().getById(shoppingId)!!
            assertEquals(home.householdId, transferred.householdId)
            assertNull(transferred.sourceItemId)
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            repository.addManualItem("Personal toothpaste", "Personal Care", 1, 4.0, "Me")
            assertNull(manager.currentSession()!!.householdId)
            assertEquals(listOf(privateStockId), db.groceryDao().getAllItemsOnce().map { it.id })
            assertEquals("Personal toothpaste", db.shoppingListDao().getShoppingListItems().first().single().itemName)
            account(db, "bob")
            manager.joinHousehold(home.joinCode)
            assertEquals(shoppingId, db.shoppingListDao().getShoppingListItems().first().single().id)
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            // A member can explicitly transfer a shared shopping task into their own private list.
            repository.assignTo(transferred, "Me")
            assertEquals("bob", db.shoppingListDao().getById(shoppingId)!!.userId)
            assertNull(db.shoppingListDao().getById(shoppingId)!!.householdId)
        } finally { db.close() }
    }

    @Test fun staleLowStockSyncCannotClearTheNextAccountsShopping(): Unit = runBlocking {
        val db = database()
        try {
            account(db, "alice")
            db.groceryDao().insertWithLog(grocery())
            val stale = db.groceryDao().getAllItemsOnce()
            account(db, "bob")
            db.groceryDao().insertWithLog(grocery())
            db.shoppingListDao().approveRestocks(db.groceryDao().getAllItemsOnce().associate { it.id to 1 })
            db.shoppingListDao().syncLowStockItems(db.groceryDao().getAllItemsOnce())
            assertEquals(1, db.shoppingListDao().getShoppingListItems().first().size)
            db.shoppingListDao().syncLowStockItems(stale)
            db.shoppingListDao().syncLowStockItems(emptyList(), com.xmum.smartgrocery.data.local.dao.DataScope("alice", null))
            assertEquals(1, db.shoppingListDao().getShoppingListItems().first().size)
        } finally { db.close() }
    }

    @Test fun version9UpgradePreservesLegacyRowsWithoutExposingThemToNewAccounts(): Unit = runBlocking {
        val name = "account-scope-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        var db: AppDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("database/schema-v8.sql").bufferedReader().use { it.readText() }
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(old::execSQL)
                old.execSQL("ALTER TABLE checklist_history ADD COLUMN stateJson TEXT NOT NULL DEFAULT ''")
                old.execSQL("INSERT INTO grocery_items VALUES (41, 'Eggs', 3, 2.5, 'Food & Beverage', NULL, 0, 1800000000000, 'Dairy')")
                old.execSQL("INSERT INTO inventory_logs VALUES (51, 41, 'Eggs', 'Food & Beverage', 'ADD', 3, 2.5, 1800000000000)")
                old.execSQL("INSERT INTO budget_entries VALUES ('2026-11', 250.75)")
                old.execSQL("INSERT INTO shopping_list_items VALUES (61, 41, 'Eggs', 'Food & Beverage', 2, '', 2.5, 'Voice', 'Me', 0, 1800000000000, 1800000000001, 'Dairy')")
                old.execSQL("INSERT INTO checklist_history VALUES ('old-1', 'Dinner', 2, 'pending', 1000, '[]', '[]', '[]', '{}')")
                old.version = 9
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13).build()
            db = migrated
            assertEquals(13, migrated.openHelper.writableDatabase.version) // Runs Room's full schema validation.
            assertTrue(migrated.groceryDao().getAllItemsOnce().isEmpty())
            account(migrated, "new-user")
            assertTrue(migrated.groceryDao().getAllItemsOnce().isEmpty())
            assertTrue(migrated.shoppingListDao().getShoppingListItems().first().isEmpty())
            assertTrue(migrated.checklistHistoryDao().getAllChecklistHistorySync().isEmpty())
            assertNull(migrated.budgetDao().getBudgetForMonthSync("2026-11"))
            val sql = migrated.openHelper.readableDatabase
            sql.query("SELECT itemId, userId FROM inventory_logs WHERE id=51").use {
                assertTrue(it.moveToFirst()); assertEquals(41, it.getInt(0)); assertEquals(AccountScopeMigration.LEGACY_USER_ID, it.getString(1))
            }
            for (table in listOf("grocery_items", "inventory_logs", "shopping_list_items", "checklist_history", "budget_entries")) {
                sql.query("SELECT COUNT(*) FROM $table WHERE userId = 'legacy-unclaimed'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            assertNotNull(SessionManager(migrated).signIn("legacy-unclaimed@local.invalid", "anything123"))
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}

package com.xmum.smartgrocery.data.local

import com.xmum.smartgrocery.data.local.dao.getBudgetForMonthSync
import com.xmum.smartgrocery.data.local.dao.setBudget
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.*
import com.xmum.smartgrocery.data.repository.HouseholdRepository
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.ui.shopping.HouseholdInvite
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HouseholdDetailsStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @Test fun ownerMemberInvitationAndLastActivityAreObservedAndInvitesDoNotGrantAccess(): Unit = runBlocking {
        val db = database()
        try {
            db.useTestAccount("alice")
            val manager = SessionManager(db)
            val home = manager.createHousehold("Garden pantry", "Our family groceries")
            val dao = db.householdDao()
            val repository = HouseholdRepository(db)
            assertEquals("Our family groceries", dao.observeHousehold().first()!!.description)
            assertEquals("Owner", dao.observeMembers().first().single().role)
            assertTrue(dao.observeMembers().first().single().isCurrentUser)
            db.groceryDao().insertWithLog(GroceryItem(name = "Shared rice", quantity = 2, price = 5.0, category = "Pantry"))
            db.useTestAccount("bob")
            db.accountDao().setSession(LocalSessionEntity(userId = "alice", householdId = home.householdId))
            repository.inviteMember(home.householdId, "bob@example.test")
            assertEquals("Invited", dao.observeMembers().first().single { it.userId == "bob" }.status)
            db.accountDao().setSession(LocalSessionEntity(userId = "bob", householdId = home.householdId))
            assertNull(dao.observeHousehold().first())
            assertTrue(db.groceryDao().getAllItemsOnce().isEmpty())
            assertTrue(runCatching { manager.selectHousehold(home.householdId) }.isFailure)
            assertTrue(runCatching { db.groceryDao().insert(GroceryItem(name = "Blocked", quantity = 1, price = 1.0, category = "Pantry")) }.isFailure)
            manager.joinHousehold(home.joinCode)
            val bob = dao.observeMembers().first().single { it.userId == "bob" }
            assertEquals("Member", bob.role)
            assertEquals("Active", bob.status)
            assertNotNull(bob.lastSeenAt)
            assertEquals(1, db.groceryDao().getAllItemsOnce().size)
            assertTrue(runCatching { repository.updateDetails(home.householdId, "Unauthorized", "") }.isFailure)
            manager.selectScope(false)
            assertEquals(home.householdId, manager.currentSession()!!.activeHouseholdId)
            assertEquals(home.name, dao.observeHousehold().first()!!.name)
            db.accountDao().setSession(LocalSessionEntity(userId = "alice", householdId = home.householdId))
            val changes = Channel<String>(Channel.UNLIMITED)
            val collector = launch { dao.observeHousehold().collect { it?.let { home -> changes.send(home.name) } } }
            try {
                withTimeout(5000) { assertEquals("Garden pantry", changes.receive()) }
                repository.updateDetails(home.householdId, "Renamed pantry", "Updated household description")
                withTimeout(5000) { while (changes.receive() != "Renamed pantry") { } }
                assertEquals("Updated household description", dao.observeHousehold().first()!!.description)
            } finally { collector.cancelAndJoin(); changes.close() }
        } finally { db.close() }
    }

    @Test fun warningStepperIsAtomicClampedAndIsolatedBetweenHouseholds(): Unit = runBlocking {
        val db = database()
        try {
            db.useTestAccount()
            val manager = SessionManager(db)
            val first = manager.createHousehold("First home")
            val second = manager.createHousehold("Second home")
            val repository = HouseholdRepository(db)
            assertTrue(runCatching { repository.adjustWarningDays(first.householdId, 1) }.isFailure)
            (1..40).map { async { repository.adjustWarningDays(second.householdId, 1) } }.awaitAll()
            assertEquals(30, db.householdDao().observeSettings().first()!!.expiryWarningDays)
            (1..40).map { async { repository.adjustWarningDays(second.householdId, -1) } }.awaitAll()
            assertEquals(1, db.householdDao().observeSettings().first()!!.expiryWarningDays)
            manager.selectHousehold(first.householdId)
            assertEquals(3, db.householdDao().observeSettings().first()!!.expiryWarningDays)
            assertEquals(3, db.householdDao().observeScopedWarningDays().first())
            manager.selectScope(false)
            assertNull(db.householdDao().observeScopedWarningDays().first())
            assertEquals(3, db.householdDao().observeSettings().first()!!.expiryWarningDays)
            repository.adjustWarningDays(first.householdId, 1)
            assertEquals(4, db.householdDao().observeSettings().first()!!.expiryWarningDays)
            assertNull(db.householdDao().observeScopedWarningDays().first())
        } finally { db.close() }
    }

    @Test fun personalAndHouseholdToggleRetainsTheChosenHouseholdAndSeparatesBudgetsAndSpending(): Unit = runBlocking {
        val db = database()
        try {
            db.useTestAccount("alice")
            val manager = SessionManager(db)
            val budget = db.budgetDao()
            budget.setBudget(BudgetEntry("2026-11", 100.0))
            db.groceryDao().insertWithLog(GroceryItem(name = "Private stock", quantity = 2, price = 3.0, category = "Dairy"))
            val first = manager.createHousehold("First")
            budget.setBudget(BudgetEntry("2026-11", 500.0))
            val chosen = manager.createHousehold("Chosen")
            budget.setBudget(BudgetEntry("2026-11", 900.0))
            db.groceryDao().insertWithLog(GroceryItem(name = "Shared stock", quantity = 3, price = 10.0, category = "Pantry"))
            manager.selectScope(false)
            assertEquals(chosen.householdId, manager.currentSession()!!.activeHouseholdId)
            assertEquals(100.0, budget.getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(6.0, budget.getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
            assertEquals(6.0, budget.getCategorySpending(0, Long.MAX_VALUE).first().sumOf { it.categoryTotal }, 0.0)
            val stalePersonal = DataScope("alice", null)
            manager.selectScope(true)
            assertEquals(chosen.householdId, manager.currentSession()!!.householdId)
            assertEquals(900.0, budget.getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            assertEquals(30.0, budget.getTotalSpentForMonth(0, Long.MAX_VALUE).first()!!, 0.0)
            assertTrue(runCatching { budget.setBudget(BudgetEntry("2026-11", 123.0), stalePersonal) }.isFailure)
            budget.setBudget(BudgetEntry("2026-11", 950.0), DataScope("alice", chosen.householdId))
            db.useTestAccount("bob")
            manager.joinHousehold(chosen.joinCode)
            assertEquals(950.0, budget.getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            db.groceryDao().insertWithLog(GroceryItem(name = "Bob's shared stock", quantity = 2, price = 5.0, category = "Pantry"))
            assertEquals(40.0, budget.getCategorySpending(0, Long.MAX_VALUE).first().sumOf { it.categoryTotal }, 0.0)
            manager.selectScope(false)
            assertNull(budget.getBudgetForMonthSync("2026-11"))
            db.accountDao().setSession(LocalSessionEntity(userId = "alice", householdId = first.householdId))
            assertEquals(500.0, budget.getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
        } finally { db.close() }
    }

    @Test fun version10MigrationPreservesScopesAddsOwnersAndPersistsSettingsAcrossReopen(): Unit = runBlocking {
        val name = "household-v11-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        var database: AppDatabase? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("database/schema-v10.sql").bufferedReader().use { it.readText() }
                    .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                fixture.split(';').map(String::trim).filter(String::isNotEmpty).forEach(old::execSQL)
                old.execSQL("INSERT INTO users VALUES ('alice','alice@example.test','Alice','','',600000,1)")
                old.execSQL("INSERT INTO users VALUES ('bob','bob@example.test','Bob','','',600000,2)")
                old.execSQL("INSERT INTO households VALUES ('home','Our home','123456')")
                old.execSQL("INSERT INTO household_members VALUES ('home','alice')")
                old.execSQL("INSERT INTO household_members VALUES ('home','bob')")
                old.execSQL("INSERT INTO local_session VALUES (1,'alice','home')")
                old.execSQL("INSERT INTO budget_entries VALUES ('2026-11', 600, 'alice', 'home', 'h:home')")
                old.version = 10
            }
            var db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13).build()
            database = db
            assertEquals(13, db.openHelper.writableDatabase.version)
            assertEquals("home", db.accountDao().session()!!.activeHouseholdId)
            val members = db.householdDao().observeMembers().first()
            assertEquals("Owner", members.single { it.userId == "alice" }.role)
            assertEquals("Member", members.single { it.userId == "bob" }.role)
            assertTrue(members.all { it.status == "Active" && it.lastSeenAt == null })
            assertEquals(600.0, db.budgetDao().getBudgetForMonthSync("2026-11")!!.limitAmount, 0.0)
            HouseholdRepository(db).adjustWarningDays("home", 1)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build(); database = db
            assertEquals(4, db.householdDao().observeSettings().first()!!.expiryWarningDays)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test fun invitationShareIntentContainsTheStoredCodeAndResolvesToTheJoinScreen() {
        val intent = HouseholdInvite.shareIntent("Garden pantry", "654321")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("smartgrocery://household/join?code=654321"))
        val link = Intent(Intent.ACTION_VIEW, Uri.parse(HouseholdInvite.link("654321"))).setPackage(context.packageName)
        assertNotNull(context.packageManager.resolveActivity(link, 0))
    }
}

package com.xmum.smartgrocery.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.BuildConfig
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.session.SessionManager
import com.xmum.smartgrocery.data.sharing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class PhoneServerConfigurationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun device() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @Test fun phoneApkOverridesSavedEmulatorAddressAndRejectsExternalServerSwitch() {
        assumeTrue("Requires the distributed phone APK built with -PAPP_BASE_URL", BuildConfig.MANAGED_SERVER)
        assertEquals(BuildConfig.AI_BASE_URL, BuildConfig.SHARING_BASE_URL)
        val prefs = context.getSharedPreferences("household_sharing", Context.MODE_PRIVATE)
        val old = prefs.getString("server_url", null)
        val db = device()
        try {
            prefs.edit().putString("server_url", "http://10.0.2.2:8790/").commit()
            SharingRuntime.disabledForTests = false
            val runtime = SharingRuntime.install(context, db)!!
            assertEquals(BuildConfig.SHARING_BASE_URL, runtime.serverUrl.value)
            assertFalse(prefs.contains("server_url"))
            try {
                runtime.configureServer("http://10.0.2.2:8790/")
                fail("A shared invitation cannot silently replace the bundled service")
            } catch (_: IllegalArgumentException) { }
            assertEquals(BuildConfig.SHARING_BASE_URL, runtime.serverUrl.value)
        } finally {
            SharingRuntime.uninstallForTests(db)
            SharingRuntime.disabledForTests = true
            prefs.edit().apply { if (old == null) remove("server_url") else putString("server_url", old) }.commit()
            db.close()
        }
    }

    @Test fun gatewayLoginKeepsPrivateDataHouseholdIdentityAndQueuedOfflinePurchases(): Unit = runBlocking {
        val a = device(); val b = device()
        val direct = "http://10.0.2.2:8791/"; val gateway = "http://10.0.2.2:8792/"
        val email = "relocation-${UUID.randomUUID()}@example.test"
        val password = "correct123"
        try {
            val old = SharingService(a, { direct }); val member = SharingService(b, { gateway })
            assertNull(old.register("Alice", email, password))
            val userId = a.accountDao().session()!!.userId!!
            a.groceryDao().insertWithLog(GroceryItem(name = "Private", quantity = 2, price = 3.0, category = "Pantry"))
            val home = old.createHousehold("Gateway family", "")
            a.groceryDao().insertWithLog(GroceryItem(name = "Shared", quantity = 1, price = 4.0, category = "Pantry"))
            old.synchronize()
            assertNull(member.register("Ben", "member-${UUID.randomUUID()}@example.test", password))
            member.joinHousehold(home.joinCode)
            // A purchase added without synchronization must retain its original outbox/entity IDs.
            a.groceryDao().insertWithLog(GroceryItem(name = "Offline purchase", quantity = 2, price = 5.0, category = "Pantry"))
            assertTrue(a.sharingDao().pending().first().isNotEmpty())
            // Even an expired/removed token must not orphan the cached household during an update.
            a.sharingDao().forgetAccount(userId, direct)
            val updated = SharingService(a, { gateway })
            assertNull(updated.login(email, password))
            updated.synchronize(); member.synchronize()
            assertEquals(userId, a.accountDao().session()!!.userId)
            assertEquals(home.householdId, a.accountDao().session()!!.activeHouseholdId)
            assertEquals(listOf(home.householdId), a.accountDao().householdsFor(userId).map { it.householdId })
            assertEquals(2, b.groceryDao().getAllItemsOnce().size)
            assertFalse(b.groceryDao().getAllItemsOnce().any { it.name == "Private" })
            assertEquals(1, b.groceryDao().getPurchaseHistory().first().count { it.itemName == "Offline purchase" })
            repeat(3) { updated.synchronize(); member.synchronize() }
            assertEquals(1, b.groceryDao().getPurchaseHistory().first().count { it.itemName == "Offline purchase" })
            assertTrue(a.sharingDao().pending().first().isEmpty())
            SessionManager(a).selectScope(false)
            assertEquals("Private", a.groceryDao().getAllItemsOnce().single().name)
            // An unreachable service still permits a verified cached login, with friendly errors for new accounts.
            val offline = SharingService(a, { "http://127.0.0.1:1/" })
            assertNull(offline.login(email, password))
            val error = offline.login("unknown@example.test", password)!!
            assertTrue(error.contains("Cannot connect")); assertFalse(error.contains("127.0.0.1"))
        } finally { a.close(); b.close() }
    }
}

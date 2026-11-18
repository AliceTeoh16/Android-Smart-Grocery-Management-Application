package com.xmum.smartgrocery.ui.common

import android.content.Context
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.room.withTransaction
import java.util.WeakHashMap

/** Local Room observation works offline; shopping writes always require a separate approval. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestockMonitor(context: Context, private val db: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = SessionManager(db)
    private val notifications = NotificationHelper(context)
    private val preferences = context.getSharedPreferences("restock_notifications", Context.MODE_PRIVATE)

    private val wakeups = MutableStateFlow(0)
    fun refresh() { wakeups.value++ }

    fun start() = scope.launch {
        manager.sessions.map { it?.userId }.distinctUntilChanged().collectLatest { actor ->
            notifications.cancelRestockNotifications()
            if (actor == null) return@collectLatest
            db.withTransaction {
                if (manager.currentUserId() == actor) db.shoppingListDao().removeUnapprovedRestocks()
            }
            combine(db.groceryDao().observeAccessibleItems(), db.shoppingListDao().observeUnapprovedRestockCount(), wakeups) {
                items, automatic, _ -> items to automatic
            }.collect { (items, automatic) ->
                if (automatic > 0) db.withTransaction {
                    if (manager.currentUserId() == actor) db.shoppingListDao().removeUnapprovedRestocks()
                }
                if (manager.currentUserId() != actor) return@collect
                val homes = manager.households().associateBy { it.householdId }
                val low = items.filter { it.quantity <= it.threshold }
                val keys = low.map { "$actor:${it.householdId ?: "personal"}:${it.id}" }.toSet()
                notifications.retainRestockNotifications(low.map { "restock:$actor:" + DataScope(actor, it.householdId).key + ":" + it.id }.toSet())
                val previous = preferences.getStringSet(actor, emptySet()).orEmpty()
                val notified = previous.intersect(keys).toMutableSet()
                low.forEach { item ->
                    val key = "$actor:${item.householdId ?: "personal"}:${item.id}"
                    if (key !in notified && manager.currentUserId() == actor) {
                        val name = item.householdId?.let { homes[it]?.name } ?: "Personal (private)"
                        if (notifications.sendRestockNotification(item, DataScope(actor, item.householdId), name)) notified.add(key)
                    }
                }
                preferences.edit().putStringSet(actor, notified).apply()
            }
        }
    }

    fun stop() { scope.cancel(); notifications.cancelRestockNotifications() }

    companion object {
        private val instances = WeakHashMap<AppDatabase, RestockMonitor>()
        @Synchronized fun install(context: Context, db: AppDatabase): RestockMonitor? {
            if (com.xmum.smartgrocery.data.sharing.SharingRuntime.disabledForTests) return null
            return instances.getOrPut(db) { RestockMonitor(context.applicationContext, db).also { it.start() } }
        }
    }
}

package com.xmum.smartgrocery.data.sharing

import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.local.entity.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

data class PendingChange(val mark: SyncEntity, val data: JsonObject?, val mutation: RemoteMutation)

/** Durable transactional outbox. Every selection and mutation is restricted to a linked household. */
class SharingEngine(private val db: AppDatabase, private val api: SharingApi, private val cipher: TokenCipher) {
    private val dao get() = db.sharingDao()
    private val mutex = Mutex()
    private val gson = GsonBuilder().serializeNulls().create()
    private val order = mapOf("household" to 0, "settings" to 1, "inventory" to 2, "logs" to 3, "shopping" to 4, "budget" to 5)

    suspend fun prepare() = db.withTransaction { SharingSchema.install(db.openHelper.writableDatabase) }

    suspend fun sync(account: CloudAccount) = mutex.withLock {
        val token = cipher.decrypt(account.encryptedToken)
        val homes = api.homes(token).households
        val remoteIds = homes.map { it.household.id }.toSet()
        for (link in dao.households(account.serverUrl)) {
            if (link.remoteHouseholdId !in remoteIds && dao.active(link.localHouseholdId, account.localUserId) > 0) revoke(account, link)
        }
        for (snapshot in homes) {
            // First import membership and baselines. Pending local edits are rebased, never overwritten.
            val known = dao.household(account.serverUrl, snapshot.household.id)
            val retrying = known != null && dao.outbox(known.localHouseholdId).isNotEmpty()
            val local = applyLocked(account, if (retrying) snapshot.copy(records = emptyList()) else snapshot, emptyMap(), null)
            val link = checkNotNull(dao.household(local))
            val pending = capture(account, link)
            val result = try { api.sync(token, link.remoteHouseholdId, pending.map { it.mutation }) }
            catch (error: SharingException) { if (error.status == 403) revoke(account, link); throw error }
            applyLocked(account, result, pending.associateBy { it.mark.type to it.mark.remoteId }, local)
        }
    }

    suspend fun importSnapshot(account: CloudAccount, snapshot: RemoteSnapshot, localId: String? = null): String = mutex.withLock {
        applyLocked(account, snapshot, emptyMap(), localId)
    }

    private suspend fun ensureCurrent(account: CloudAccount) {
        check(db.accountDao().session()?.userId == account.localUserId) { "Account changed. Try again after signing in." }
        check(dao.account(account.localUserId, account.serverUrl)?.remoteUserId == account.remoteUserId) { "Sharing session changed." }
    }

    private suspend fun revoke(account: CloudAccount, link: CloudHousehold) = db.withTransaction {
        ensureCurrent(account)
        db.accountDao().deleteMembership(link.localHouseholdId, account.localUserId)
        dao.discardPending(link.localHouseholdId)
        dao.discardOutbox(link.localHouseholdId)
        val session = db.accountDao().session()!!
        if (session.householdId == link.localHouseholdId || session.activeHouseholdId == link.localHouseholdId)
            db.accountDao().setSession(session.copy(householdId = null, activeHouseholdId = null))
    }

    private suspend fun resolveUser(account: CloudAccount, id: String, name: String = "Former member", email: String = ""): String {
        val existing = dao.user(account.serverUrl, id)
        val local = if (id == account.remoteUserId) account.localUserId else existing?.localUserId ?: "remote:${namespace(account.serverUrl)}:$id"
        val old = dao.localUser(local)
        if (old == null) db.accountDao().insertUser(UserEntity(local, "remote-$id@${namespace(account.serverUrl)}.invalid", name, "", "", 600000))
        else if (email.isNotBlank() && old.displayName != name) dao.updateUser(old.copy(displayName = name))
        val realEmail = email.ifBlank { existing?.email.orEmpty() }
        if (existing == null || existing.localUserId != local || existing.email != realEmail) dao.user(CloudUser(account.serverUrl, id, local, realEmail))
        return local
    }

    private suspend fun applyLocked(account: CloudAccount, snapshot: RemoteSnapshot, sent: Map<Pair<String, String>, PendingChange>, preferredId: String?): String = db.withTransaction {
        ensureCurrent(account)
        val link = dao.household(account.serverUrl, snapshot.household.id)
        val local = link?.localHouseholdId ?: preferredId ?: "shared:${namespace(account.serverUrl)}:${snapshot.household.id}"
        dao.applying(true)
        try {
            val before = dao.localHousehold(local)
            val home = HouseholdEntity(local, snapshot.household.name, snapshot.household.joinCode, snapshot.household.description)
            if (before == null) db.accountDao().insertHousehold(home)
            else if (before != home && dao.marks(local).none { it.type == "household" && it.dirty }) dao.household(home)
            if (link == null) dao.household(CloudHousehold(local, account.serverUrl, snapshot.household.id))
            val profiles = snapshot.members.associate { member ->
                member.id to resolveUser(account, member.id, member.name, member.email)
            }
            // Only current server memberships grant cached access; invitations remain unreadable.
            dao.clearMembers(local)
            snapshot.members.forEach { member -> db.accountDao().insertMember(HouseholdMemberEntity(local,
                profiles.getValue(member.id), member.role, member.status, member.lastSeenAt)) }
            val marks = dao.marks(local).associateBy { it.type to it.remoteId }.toMutableMap()
            for (remote in snapshot.records.sortedBy { order[it.collection] ?: 99 }) {
                var mark = marks[remote.collection to remote.id]
                if (mark != null && remote.version < mark.version) continue
                val transmitted = sent[remote.collection to remote.id]
                val acknowledged = transmitted != null && snapshot.acknowledged.orEmpty().contains(transmitted.mutation.mutationId)
                val hasNewEdits = mark?.dirty == true && (!acknowledged || mark.revision != transmitted!!.mark.revision)
                val localData = mark?.let { encode(it, dao.marks(local)) }
                if (remote.deleted) {
                    // An explicit move back to shared while its delete was in flight creates a fresh record.
                    if (hasNewEdits && transmitted?.mark?.deleted == true && mark?.deleted == false && localData != null) {
                        dao.mark(mark.copy(remoteId = UUID.randomUUID().toString(), version = 0, baseline = "", dirty = true))
                        continue
                    }
                    if (mark != null) delete(mark)
                    val key = mark?.localKey ?: remote.id
                    dao.mark(SyncEntity(local, remote.collection, key, remote.id, remote.version, remote.data.toString(), false, true, mark?.revision ?: 1))
                    continue
                }
                if (mark != null && !mark.dirty && mark.version == remote.version) continue
                var data = remote.data.deepCopy()
                var dirty = false
                var deleted = false
                if (hasNewEdits) {
                    deleted = mark!!.deleted
                    if (deleted) dirty = true
                    else if (localData != null) {
                        val base = transmitted?.data ?: parse(mark.baseline)
                        val patch = diff(localData, base)
                        val quantity = quantityField(remote.collection)
                        for ((field, value) in patch.entrySet()) if (field != quantity) data.add(field, value)
                        if (quantity != null) {
                            val delta = int(localData, quantity) - int(base, quantity)
                            data.addProperty(quantity, (int(remote.data, quantity).toLong() + delta).coerceIn(0, 2_000_000_000).toInt())
                            dirty = delta != 0 || patch.entrySet().any { it.key != quantity }
                        } else dirty = patch.size() > 0
                    }
                }
                val owner = profiles[remote.userId] ?: resolveUser(account, remote.userId)
                val localKey = if (deleted) mark!!.localKey else store(local, remote.collection, mark?.localKey, owner, data)
                mark = SyncEntity(local, remote.collection, localKey, remote.id, remote.version, remote.data.toString(), dirty, deleted, mark?.revision ?: 1)
                dao.mark(mark)
                marks[remote.collection to remote.id] = mark
            }
            if (sent.isNotEmpty()) dao.acknowledge(snapshot.acknowledged.orEmpty())
        } finally { dao.applying(false) }
        local
    }

    private suspend fun capture(account: CloudAccount, link: CloudHousehold): List<PendingChange> = db.withTransaction {
        ensureCurrent(account)
        check(dao.active(link.localHouseholdId, account.localUserId) > 0) { "Household access changed." }
        val queued = dao.outbox(link.localHouseholdId)
        if (queued.isNotEmpty()) return@withTransaction queued.map { stored ->
            PendingChange(gson.fromJson(stored.markJson, SyncEntity::class.java),
                stored.dataJson.takeIf { it.isNotEmpty() }?.let { parse(it) }, gson.fromJson(stored.mutationJson, RemoteMutation::class.java))
        }
        var marks = dao.marks(link.localHouseholdId)
        val result = mutableListOf<PendingChange>()
        for (original in marks.filter { it.dirty }.sortedBy { order[it.type] ?: 99 }.take(200)) {
            var mark = original
            val data = if (mark.deleted) null else encode(mark, marks)
            if (!mark.deleted && data == null) continue
            // Both devices' auto-restock suggestion for the same stock row has one shared identity.
            if (mark.type == "shopping" && mark.version == 0L && data?.get("sourceId")?.isJsonNull == false) {
                val canonical = "auto:${data.get("sourceId").asString}"
                if (mark.remoteId != canonical && marks.none { it.type == "shopping" && it.remoteId == canonical }) {
                    mark = mark.copy(remoteId = canonical); dao.mark(mark)
                    marks = dao.marks(link.localHouseholdId)
                }
            }
            val base = parse(mark.baseline)
            val patch = if (mark.version == 0L) data else data?.let { diff(it, base) }
            val quantity = quantityField(mark.type)
            val delta = if (quantity != null && mark.version > 0 && data != null) int(data, quantity) - int(base, quantity) else null
            val mutation = RemoteMutation("${dao.deviceId()}:${mark.remoteId}:${mark.revision}", mark.type, mark.remoteId, mark.version, mark.deleted, patch, delta)
            result += PendingChange(mark, data, mutation)
            dao.enqueue(PendingOperation(mutation.mutationId, link.localHouseholdId, gson.toJson(mark), data?.toString().orEmpty(), gson.toJson(mutation)))
        }
        result
    }

    private suspend fun encode(mark: SyncEntity, marks: List<SyncEntity>): JsonObject? {
        val hh = mark.householdId
        fun reference(id: Int?) = id?.let { key -> marks.firstOrNull { it.type == "inventory" && it.localKey == key.toString() && !it.deleted }?.remoteId }
        val data: Map<String, Any?> = when (mark.type) {
            "inventory" -> dao.inventory(hh, mark.localKey.toInt())?.let { mapOf("name" to it.name, "quantity" to it.quantity, "price" to it.price, "category" to it.category,
                "subcategory" to it.subcategory, "expiryDate" to it.expiryDate, "threshold" to it.threshold, "purchasedDate" to it.purchasedDate,
                "addedByName" to it.addedByName, "lastModifiedByName" to it.lastModifiedByName,
                "lastModifiedAction" to it.lastModifiedAction, "lastModifiedAt" to it.lastModifiedAt) }
            "shopping" -> dao.shopping(hh, mark.localKey.toInt())?.let { mapOf("itemName" to it.itemName, "quantityNeeded" to it.quantityNeeded, "quantityLabel" to it.quantityLabel,
                "estimatedUnitPrice" to it.estimatedUnitPrice, "category" to it.category, "subcategory" to it.subcategory, "addedBy" to it.addedBy,
                "isChecked" to it.isChecked, "sourceId" to reference(it.sourceItemId), "createdAt" to it.createdAt, "updatedAt" to it.updatedAt,
                "addedByName" to it.addedByName, "lastModifiedByName" to it.lastModifiedByName,
                "lastModifiedAction" to it.lastModifiedAction, "lastModifiedAt" to it.lastModifiedAt) }
            "logs" -> dao.log(hh, mark.localKey.toInt())?.let { mapOf("itemId" to reference(it.itemId), "itemName" to it.itemName, "category" to it.category,
                "changeType" to it.changeType, "amountChanged" to it.amountChanged, "unitPriceAtChange" to it.unitPriceAtChange, "timestamp" to it.timestamp) }
            "budget" -> dao.budget(hh, mark.localKey)?.let { mapOf("monthYear" to it.monthYear, "limitAmount" to it.limitAmount) }
            "household" -> dao.localHousehold(hh)?.let { mapOf("name" to it.name, "description" to it.description) }
            "settings" -> dao.settings(hh)?.let { mapOf("expiryWarningDays" to it.expiryWarningDays) }
            else -> null
        } ?: return null
        return gson.toJsonTree(data).asJsonObject
    }

    private suspend fun store(hh: String, type: String, key: String?, owner: String, data: JsonObject): String {
        val marks = dao.marks(hh)
        suspend fun reference(name: String): Int? {
            val remote = stringOrNull(data, name) ?: return null
            val value = marks.firstOrNull { it.type == "inventory" && it.remoteId == remote && !it.deleted } ?: return null
            return value.localKey.toInt().takeIf { dao.inventory(hh, it) != null }
        }
        return when (type) {
            "inventory" -> {
                val previous = key?.toIntOrNull()?.let { dao.inventory(hh, it) }
                val value = GroceryItem(previous?.id ?: 0, string(data, "name"), int(data, "quantity"), data["price"].asDouble,
                    string(data, "category"), longOrNull(data, "expiryDate"), int(data, "threshold"), data["purchasedDate"].asLong,
                    stringOrNull(data, "subcategory"), owner, hh, stringOrNull(data, "addedByName"),
                    stringOrNull(data, "lastModifiedByName"), stringOrNull(data, "lastModifiedAction"), longOrNull(data, "lastModifiedAt"))
                if (previous == null) dao.insertInventory(value).toString() else { if (previous != value) dao.inventory(value); previous.id.toString() }
            }
            "shopping" -> {
                val previous = key?.toIntOrNull()?.let { dao.shopping(hh, it) }
                val source = reference("sourceId")
                // Adopt an existing local auto suggestion rather than collide on sourceItemId.
                val existing = previous ?: source?.let { dao.shoppingBySource(hh, it) }
                val value = ShoppingListItem(id = existing?.id ?: 0, sourceItemId = source, itemName = string(data, "itemName"), category = string(data, "category"),
                    quantityNeeded = int(data, "quantityNeeded"), quantityLabel = string(data, "quantityLabel"), estimatedUnitPrice = data["estimatedUnitPrice"].asDouble,
                    addedBy = string(data, "addedBy"), assignedTo = "Household", isChecked = data["isChecked"].asBoolean,
                    createdAt = data["createdAt"].asLong, updatedAt = data["updatedAt"].asLong, subcategory = stringOrNull(data, "subcategory"), userId = owner, householdId = hh,
                    addedByName = stringOrNull(data, "addedByName"), lastModifiedByName = stringOrNull(data, "lastModifiedByName"),
                    lastModifiedAction = stringOrNull(data, "lastModifiedAction"), lastModifiedAt = longOrNull(data, "lastModifiedAt"))
                if (existing == null) dao.insertShopping(value).toString() else { if (existing != value) dao.shopping(value); existing.id.toString() }
            }
            "logs" -> {
                val previous = key?.toIntOrNull()?.let { dao.log(hh, it) }
                previous?.id?.toString() ?: dao.insertLog(InventoryLog(itemId = reference("itemId"), itemName = string(data, "itemName"), category = string(data, "category"),
                    changeType = string(data, "changeType"), amountChanged = int(data, "amountChanged"), unitPriceAtChange = data["unitPriceAtChange"].asDouble,
                    timestamp = data["timestamp"].asLong, userId = owner, householdId = hh)).toString()
            }
            "budget" -> {
                val value = BudgetEntry(string(data, "monthYear"), data["limitAmount"].asDouble, owner, hh, "h:$hh")
                if (dao.budget(hh, value.monthYear) != value) dao.budget(value)
                value.monthYear
            }
            "household" -> { val old = checkNotNull(dao.localHousehold(hh)); val value = old.copy(name = string(data, "name"), description = string(data, "description")); if (old != value) dao.household(value); hh }
            "settings" -> { val value = HouseholdSettingEntity(hh, int(data, "expiryWarningDays")); if (dao.settings(hh) != value) dao.settings(value); hh }
            else -> error("Unknown shared record.")
        }
    }

    private suspend fun delete(mark: SyncEntity) {
        when (mark.type) {
            "inventory" -> mark.localKey.toIntOrNull()?.let { dao.deleteInventory(mark.householdId, it) }
            "shopping" -> mark.localKey.toIntOrNull()?.let { dao.deleteShopping(mark.householdId, it) }
            "budget" -> dao.deleteBudget(mark.householdId, mark.localKey)
        }
    }

    private fun parse(value: String): JsonObject = if (value.isBlank()) JsonObject() else JsonParser.parseString(value).asJsonObject
    private fun diff(value: JsonObject, base: JsonObject) = JsonObject().apply { value.entrySet().forEach { (key, v) -> if (base[key] != v) add(key, v) } }
    private fun quantityField(type: String) = when (type) { "inventory" -> "quantity"; "shopping" -> "quantityNeeded"; else -> null }
    private fun int(value: JsonObject, key: String) = value[key]?.takeUnless { it.isJsonNull }?.asInt ?: 0
    private fun string(value: JsonObject, key: String) = value[key].asString
    private fun stringOrNull(value: JsonObject, key: String) = value[key]?.takeUnless { it.isJsonNull }?.asString
    private fun longOrNull(value: JsonObject, key: String) = value[key]?.takeUnless { it.isJsonNull }?.asLong
    companion object {
        fun namespace(server: String): String = MessageDigest.getInstance("SHA-256").digest(server.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }
}

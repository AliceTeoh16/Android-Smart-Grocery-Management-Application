package com.xmum.smartgrocery.data.repository

import androidx.room.withTransaction
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.local.entity.*
import java.util.Locale

class HouseholdRepository(private val database: AppDatabase) {
    private val dao get() = database.householdDao()
    val household = dao.observeHousehold()
    val members = dao.observeMembers()
    val settings = dao.observeSettings()

    suspend fun adjustWarningDays(householdId: String, delta: Int) {
        require(delta == -1 || delta == 1)
        check(dao.adjustWarningDays(householdId, delta) == 1) { "Household changed. Reopen Share & Connect." }
    }

    suspend fun updateDetails(householdId: String, name: String, description: String) {
        require(name.isNotBlank()) { "Enter a household name." }
        check(dao.updateDetails(householdId, name.trim(), description.trim()) == 1) { "Only the household owner can edit these details." }
    }

    /** A local invitation is persisted; joining by code activates it. No email is sent. */
    suspend fun inviteMember(householdId: String, email: String) {
        com.xmum.smartgrocery.data.sharing.SharingRuntime.forDatabase(database)?.let { it.service.invite(householdId, email); return }
        database.withTransaction {
        val session = checkNotNull(database.accountDao().session()) { "Sign in first." }
        val actor = checkNotNull(session.userId) { "Sign in first." }
        check((session.activeHouseholdId ?: session.householdId) == householdId) { "Household changed. Reopen Share & Connect." }
        val owner = dao.membership(householdId, actor)
        check(owner?.role == HouseholdRole.OWNER && owner.status == HouseholdMemberStatus.ACTIVE) { "Only the owner can invite members." }
        val user = requireNotNull(database.accountDao().findByEmail(email.trim().lowercase(Locale.ROOT))) {
            "This email has no local account yet. Share the invitation link instead."
        }
        require(dao.membership(householdId, user.userId) == null) { "This account is already a member or invited." }
        database.accountDao().insertMember(HouseholdMemberEntity(householdId, user.userId, status = HouseholdMemberStatus.INVITED))
        }
    }
}

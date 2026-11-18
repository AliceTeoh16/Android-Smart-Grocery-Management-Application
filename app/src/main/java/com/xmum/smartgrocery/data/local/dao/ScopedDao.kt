package com.xmum.smartgrocery.data.local.dao

import androidx.room.Query

object ScopeSql {
    const val USER = "(SELECT userId FROM local_session WHERE id = 1)"
    const val ACTOR_NAME = "(SELECT displayName FROM users WHERE userId = " + USER + ")"
    const val HOUSEHOLD = "(SELECT householdId FROM local_session WHERE id = 1)"
    const val PERSONAL = "userId = " + USER
    const val ACCESSIBLE = "((householdId IS NULL AND userId = " + USER + ") OR householdId IN (SELECT m.householdId FROM household_members m WHERE m.userId = " + USER + " AND m.status = 'Active'))"
    const val MATCH = "((householdId IS NULL AND userId = " + USER + " AND " + HOUSEHOLD + " IS NULL) OR (householdId = " + HOUSEHOLD + " AND EXISTS (SELECT 1 FROM household_members m WHERE m.householdId = " + HOUSEHOLD + " AND m.userId = " + USER + " AND m.status = 'Active')))"
}

data class DataScope(val userId: String, val householdId: String?) {
    val key: String get() = householdId?.let { "h:$it" } ?: "u:$userId"
    fun requireDraft(userId: String, householdId: String? = null) {
        require(userId.isBlank() || userId == this.userId) { "Cannot write data for another account." }
        require((userId.isBlank() && householdId == null) || householdId == this.householdId) { "Select the item's household first." }
    }
}

abstract class ScopedDao {
    @Query("SELECT s.userId, s.householdId FROM local_session s JOIN users u ON u.userId = s.userId WHERE s.id = 1 AND (s.householdId IS NULL OR EXISTS (SELECT 1 FROM household_members m WHERE m.householdId = s.householdId AND m.userId = s.userId AND m.status = 'Active'))")
    protected abstract suspend fun activeScope(): DataScope?

    protected suspend fun requireScope(): DataScope = checkNotNull(activeScope()) { "Sign in and select a valid data scope first." }
}

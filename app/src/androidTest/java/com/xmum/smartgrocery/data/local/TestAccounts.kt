package com.xmum.smartgrocery.data.local

import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity
import com.xmum.smartgrocery.data.local.entity.UserEntity

/** Explicit authentication fixture; production databases never seed an active account. */
internal suspend fun AppDatabase.useTestAccount(id: String = "test-user") {
    if (accountDao().findByEmail("$id@example.test") == null) {
        accountDao().insertUser(UserEntity(id, "$id@example.test", "Test user", "", "", 600000))
    }
    accountDao().setSession(LocalSessionEntity(userId = id))
}

internal suspend fun AppDatabase.inspectLegacyAccount() {
    accountDao().setSession(LocalSessionEntity(userId = AccountScopeMigration.LEGACY_USER_ID))
}

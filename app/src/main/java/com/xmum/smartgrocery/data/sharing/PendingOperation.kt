package com.xmum.smartgrocery.data.sharing

import androidx.room.*

/** Exact transmitted payloads survive timeouts and process death, so retries never repeat quantity deltas. */
@Entity(tableName = "sync_outbox", indices = [Index("householdId")])
data class PendingOperation(@PrimaryKey val mutationId: String, val householdId: String,
    val markJson: String, val dataJson: String, val mutationJson: String)

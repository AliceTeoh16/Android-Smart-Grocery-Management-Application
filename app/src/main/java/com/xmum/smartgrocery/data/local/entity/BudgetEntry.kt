package com.xmum.smartgrocery.data.local.entity

import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "budget_entries", primaryKeys = ["scopeKey", "monthYear"], foreignKeys = [ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["householdId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("userId"), Index("householdId")])
data class BudgetEntry(
    val monthYear: String, // e.g. "2026-07"
    val limitAmount: Double,
    val userId: String = "",
    val householdId: String? = null,
    val scopeKey: String = ""
)

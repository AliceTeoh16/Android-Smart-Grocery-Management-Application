package com.xmum.smartgrocery.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "checklist_history", foreignKeys = [ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("userId")])
data class ChecklistHistoryItem(
    @PrimaryKey
    val id: String,
    val dishName: String,
    val servings: Int,
    val status: String, // "pending", "cooked", "cancelled"
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    val missingChecklistJson: String = "[]",
    val inventoryDeductedJson: String = "[]",
    val addedShoppingKeysJson: String = "[]",
    @ColumnInfo(defaultValue = "''")
    val stateJson: String = "",
    val userId: String = ""
)

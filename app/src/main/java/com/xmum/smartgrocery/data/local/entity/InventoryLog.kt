package com.xmum.smartgrocery.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Ignore

object InventoryChangeType {
    const val ADD = "ADD"
    const val DEDUCT = "DEDUCT"
    const val VOICE_DEDUCT = "VOICE_DEDUCT"
    const val RECIPE_DEDUCT = "RECIPE_DEDUCT"
}

@Entity(
    tableName = "inventory_logs",
    foreignKeys = [ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["householdId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = GroceryItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("userId"), Index("householdId"), Index("itemId"), Index("timestamp"), Index("changeType")]
)
data class InventoryLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val itemId: Int?,
    val itemName: String,
    val category: String,
    val changeType: String,
    val amountChanged: Int,
    val unitPriceAtChange: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val userId: String = "",
    val householdId: String? = null,
    @ColumnInfo(defaultValue = "'pcs'") val unit: String = "pcs"
) {
    @Ignore
    constructor(legacyId: Int, legacyItemId: Int?, legacyName: String, legacyCategory: String,
        legacyType: String, legacyAmount: Int, legacyPrice: Double, legacyTimestamp: Long,
        legacyUser: String, legacyHousehold: String?) : this(legacyId, legacyItemId, legacyName,
        legacyCategory, legacyType, legacyAmount, legacyPrice, legacyTimestamp, legacyUser, legacyHousehold, "pcs")
}

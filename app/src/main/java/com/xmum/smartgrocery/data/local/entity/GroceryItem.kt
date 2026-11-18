package com.xmum.smartgrocery.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "grocery_items", foreignKeys = [ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["householdId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("userId"), Index("householdId")])
data class GroceryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val quantity: Int,
    val price: Double,
    val category: String,
    val expiryDate: Long? = null, // null means Not Set
    val threshold: Int = 2, // low stock warning threshold
    val purchasedDate: Long = System.currentTimeMillis(),
    val subcategory: String? = null,
    val userId: String = "",
    val householdId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val addedByName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lastModifiedByName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lastModifiedAction: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lastModifiedAt: Long? = null
) {
    val sharedChangeIndicator: String? get() = SharedItemChange.label(householdId, addedByName, lastModifiedByName, lastModifiedAction)

    val categorySelection: String get() = com.xmum.smartgrocery.utils.GroceryCategories.selection(category, subcategory)

    fun normalized(): GroceryItem = copy(
        category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(category).label,
        subcategory = com.xmum.smartgrocery.utils.GroceryCategories.subcategory(category, subcategory),
        expiryDate = expiryDate?.takeIf { it > 0L })

    val isLowStock: Boolean
        get() = quantity <= threshold

    val isExpiringSoon: Boolean
        get() {
            val threeDaysInMillis = 3 * 24 * 60 * 60 * 1000L
            return expiryDate?.let { (it - System.currentTimeMillis()) in 0..threeDaysInMillis } ?: false
        }
}

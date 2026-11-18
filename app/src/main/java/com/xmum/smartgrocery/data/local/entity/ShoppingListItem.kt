package com.xmum.smartgrocery.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "shopping_list_items",
    foreignKeys = [ForeignKey(entity = UserEntity::class, parentColumns = ["userId"], childColumns = ["userId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["householdId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("userId"), Index("householdId"),
        Index(value = ["sourceItemId"], unique = true)
    ]
)
data class ShoppingListItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sourceItemId: Int? = null,
    val itemName: String,
    val category: String,
    val quantityNeeded: Int,
    @ColumnInfo(defaultValue = "''")
    val quantityLabel: String = "",
    val estimatedUnitPrice: Double,
    @ColumnInfo(defaultValue = "'Auto'")
    val addedBy: String = "Auto",
    @ColumnInfo(defaultValue = "'Household'")
    val assignedTo: String = "Household",
    val isChecked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
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
    fun normalized(): ShoppingListItem = copy(
        category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(category).label,
        subcategory = com.xmum.smartgrocery.utils.GroceryCategories.subcategory(category, subcategory))
}

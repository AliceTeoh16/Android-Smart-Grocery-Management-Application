package com.xmum.smartgrocery.data.local.entity

import androidx.room.*

@Entity(tableName = "household_settings", foreignKeys = [
    ForeignKey(entity = HouseholdEntity::class, parentColumns = ["householdId"], childColumns = ["householdId"], onDelete = ForeignKey.CASCADE)
])
data class HouseholdSettingEntity(
    @PrimaryKey val householdId: String,
    @ColumnInfo(defaultValue = "3") val expiryWarningDays: Int = 3
)

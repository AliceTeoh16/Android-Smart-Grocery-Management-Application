package com.xmum.smartgrocery.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;

import java.util.Objects;

@Entity(tableName = "budget_entries", primaryKeys = {"scopeKey", "monthYear"},
        foreignKeys = {
                @ForeignKey(entity = UserEntity.class, parentColumns = "userId", childColumns = "userId",
                        onDelete = ForeignKey.RESTRICT),
                @ForeignKey(entity = HouseholdEntity.class, parentColumns = "householdId", childColumns = "householdId",
                        onDelete = ForeignKey.RESTRICT)
        },
        indices = {@Index("userId"), @Index("householdId")}
)
public final class BudgetEntry {
    @NonNull private final String monthYear;
    private final double limitAmount;
    @NonNull private final String userId;
    @Nullable private final String householdId;
    @NonNull private final String scopeKey;

    @Ignore
    public BudgetEntry(@NonNull String monthYear, double limitAmount) {
        this(monthYear, limitAmount, "", null, "");
    }

    public BudgetEntry(@NonNull String monthYear, double limitAmount, @NonNull String userId,
            @Nullable String householdId, @NonNull String scopeKey) {
        this.monthYear = monthYear;
        this.limitAmount = limitAmount;
        this.userId = userId;
        this.householdId = householdId;
        this.scopeKey = scopeKey;
    }

    @NonNull
    public String getMonthYear() { return monthYear; }

    public double getLimitAmount() { return limitAmount; }

    @NonNull
    public String getUserId() { return userId; }

    @Nullable
    public String getHouseholdId() { return householdId; }

    @NonNull
    public String getScopeKey() { return scopeKey; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BudgetEntry)) {
            return false;
        }
        BudgetEntry entry = (BudgetEntry) other;
        return monthYear.equals(entry.monthYear) && Double.compare(limitAmount, entry.limitAmount) == 0
                && userId.equals(entry.userId) && Objects.equals(householdId, entry.householdId)
                && scopeKey.equals(entry.scopeKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(monthYear, limitAmount, userId, householdId, scopeKey);
    }
}

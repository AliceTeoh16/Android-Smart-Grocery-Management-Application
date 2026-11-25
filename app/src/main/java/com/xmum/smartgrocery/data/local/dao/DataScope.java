package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Ignore;

import java.util.Objects;

import kotlin.text.StringsKt;

public final class DataScope {
    @NonNull private final String userId;
    @Nullable private final String householdId;

    public DataScope(@NonNull String userId, @Nullable String householdId) {
        this.userId = userId;
        this.householdId = householdId;
    }

    @NonNull
    public String getUserId() { return userId; }

    @Nullable
    public String getHouseholdId() { return householdId; }

    @Ignore
    @NonNull
    public String getKey() {
        return householdId != null ? "h:" + householdId : "u:" + userId;
    }

    @NonNull
    public DataScope withHouseholdId(@Nullable String householdId) {
        return new DataScope(userId, householdId);
    }

    public void requireDraft(@NonNull String draftUserId) {
        requireDraft(draftUserId, null);
    }

    public void requireDraft(@NonNull String draftUserId, @Nullable String draftHouseholdId) {
        boolean newDraft = StringsKt.isBlank(draftUserId);
        if (!newDraft && !draftUserId.equals(userId)) {
            throw new IllegalArgumentException("Cannot write data for another account.");
        }
        if (!(newDraft && draftHouseholdId == null) && !Objects.equals(draftHouseholdId, householdId)) {
            throw new IllegalArgumentException("Select the item's household first.");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DataScope)) {
            return false;
        }
        DataScope scope = (DataScope) other;
        return userId.equals(scope.userId) && Objects.equals(householdId, scope.householdId);
    }

    @Override
    public int hashCode() {
        return userId.hashCode() * 31 + Objects.hashCode(householdId);
    }
}

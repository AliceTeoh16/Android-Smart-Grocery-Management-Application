package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class HouseholdMemberProfile {
    @NonNull private final String householdId;
    @NonNull private final String userId;
    @NonNull private final String displayName;
    @NonNull private final String email;
    @NonNull private final String role;
    @NonNull private final String status;
    @Nullable private final Long lastSeenAt;
    private final boolean isCurrentUser;

    public HouseholdMemberProfile(@NonNull String householdId, @NonNull String userId, @NonNull String displayName, @NonNull String email,
            @NonNull String role, @NonNull String status, @Nullable Long lastSeenAt, boolean isCurrentUser) {
        this.householdId = householdId;
        this.userId = userId;
        this.displayName = displayName;
        this.email = email;
        this.role = role;
        this.status = status;
        this.lastSeenAt = lastSeenAt;
        this.isCurrentUser = isCurrentUser;
    }

    @NonNull
    public String getHouseholdId() { return householdId; }

    @NonNull
    public String getUserId() { return userId; }

    @NonNull
    public String getDisplayName() { return displayName; }

    @NonNull
    public String getEmail() { return email; }

    @NonNull
    public String getRole() { return role; }

    @NonNull
    public String getStatus() { return status; }

    @Nullable
    public Long getLastSeenAt() { return lastSeenAt; }

    public boolean isCurrentUser() { return isCurrentUser; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HouseholdMemberProfile)) {
            return false;
        }
        HouseholdMemberProfile value = (HouseholdMemberProfile) other;
        return householdId.equals(value.householdId) && userId.equals(value.userId) && displayName.equals(value.displayName)
                && email.equals(value.email) && role.equals(value.role) && status.equals(value.status)
                && Objects.equals(lastSeenAt, value.lastSeenAt) && isCurrentUser == value.isCurrentUser;
    }

    @Override
    public int hashCode() {
        return Objects.hash(householdId, userId, displayName, email, role, status, lastSeenAt, isCurrentUser);
    }
}

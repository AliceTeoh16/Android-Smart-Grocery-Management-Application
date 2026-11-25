package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;

import java.util.Objects;

public final class DataContextInfo {
    @NonNull private final String name;
    private final int memberCount;

    public DataContextInfo(@NonNull String name, int memberCount) {
        this.name = name;
        this.memberCount = memberCount;
    }

    @NonNull
    public String getName() { return name; }

    public int getMemberCount() { return memberCount; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DataContextInfo)) {
            return false;
        }
        DataContextInfo value = (DataContextInfo) other;
        return name.equals(value.name) && memberCount == value.memberCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, memberCount);
    }
}

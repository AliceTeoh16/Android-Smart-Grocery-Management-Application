package com.xmum.smartgrocery.data.local.entity;

import androidx.annotation.Nullable;

import kotlin.text.StringsKt;

/** Formats the latest change attribution for a shared item. */
public final class SharedItemChange {
    private SharedItemChange() {
    }

    @Nullable
    public static String label(@Nullable String householdId, @Nullable String creator,
            @Nullable String modifier, @Nullable String action) {
        if (householdId == null) {
            return null;
        }
        if (modifier != null && !StringsKt.isBlank(modifier)
                && ("Added".equals(action) || "Updated".equals(action)
                        || "Checked".equals(action) || "Unchecked".equals(action))) {
            return action + " by " + modifier;
        }
        return creator != null && !StringsKt.isBlank(creator) ? "Added by " + creator : null;
    }
}

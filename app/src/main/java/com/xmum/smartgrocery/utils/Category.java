package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;

public enum Category {
    FOOD_BEVERAGE("Food & Beverage", 0xFFE4F3E8L, 0xFF286343L),
    HOUSEHOLD_ESSENTIALS("Household Essentials", 0xFFE2EEFFL, 0xFF315D99L),
    PERSONAL_CARE("Personal Care", 0xFFF2E6FAL, 0xFF804A99L);

    private final String label;
    private final long background;
    private final long foreground;

    Category(String label, long background, long foreground) {
        this.label = label;
        this.background = background;
        this.foreground = foreground;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    public long getBackground() {
        return background;
    }

    public long getForeground() {
        return foreground;
    }
}

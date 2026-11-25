package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;

public enum FoodSubcategory {
    PRODUCE("Produce"),
    MEAT_SEAFOOD("Meat & Seafood"),
    DAIRY("Dairy"),
    BAKERY("Bakery"),
    PANTRY("Pantry"),
    SPICES("Spices"),
    BEVERAGES("Beverages"),
    SNACKS_SWEETS("Snacks & Sweets"),
    FROZEN_FOODS("Frozen Foods");

    private final String label;

    FoodSubcategory(String label) {
        this.label = label;
    }

    @NonNull
    public String getLabel() {
        return label;
    }
}

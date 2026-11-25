package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;

public final class CategorySpend {
    private final String category;
    private final double categoryTotal;

    public CategorySpend(@NonNull String category, double categoryTotal) {
        this.category = category;
        this.categoryTotal = categoryTotal;
    }

    @NonNull
    public String getCategory() {
        return category;
    }

    public double getCategoryTotal() {
        return categoryTotal;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CategorySpend)) {
            return false;
        }
        CategorySpend spend = (CategorySpend) other;
        return category.equals(spend.category) && Double.compare(categoryTotal, spend.categoryTotal) == 0;
    }

    @Override
    public int hashCode() {
        return category.hashCode() * 31 + Double.hashCode(categoryTotal);
    }
}

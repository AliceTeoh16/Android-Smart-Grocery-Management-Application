package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;

public final class IngredientTextCandidate {
    private final String name;
    private final String category;
    private final int quantity;
    private final boolean quantityDetected;
    private final boolean selected;
    private final String source;

    public IngredientTextCandidate(@NonNull String name, @NonNull String category, int quantity,
            boolean quantityDetected, boolean selected, @NonNull String source) {
        this.name = name;
        this.category = category;
        this.quantity = quantity;
        this.quantityDetected = quantityDetected;
        this.selected = selected;
        this.source = source;
    }

    @NonNull
    public String getName() { return name; }

    @NonNull
    public String getCategory() { return category; }

    public int getQuantity() { return quantity; }

    public boolean getQuantityDetected() { return quantityDetected; }

    public boolean getSelected() { return selected; }

    @NonNull
    public String getSource() { return source; }
}

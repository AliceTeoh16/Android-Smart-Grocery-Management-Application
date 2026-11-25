package com.xmum.smartgrocery.utils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Explicit form units. Stock uses whole base units; no product sizes are guessed. */
public final class InventoryUnits {
    public static final List<String> OPTIONS = Collections.unmodifiableList(Arrays.asList(
            "pcs", "dozen", "packs", "bottles", "cartons", "cans", "bags", "boxes", "bunches", "pairs",
            "kg", "g", "mg", "L", "ml", "cups", "tbsp", "tsp", "cloves", "slices", "stalks"));
    public static final List<String> STORAGE = Collections.unmodifiableList(Arrays.asList(
            "Not set", "Pantry", "Fridge", "Freezer", "Cupboard", "Counter", "Other"));

    private InventoryUnits() { }

    public static String stockUnit(String unit) {
        requireUnit(unit);
        switch (unit) {
            case "dozen": return "pcs";
            case "kg": return "g";
            case "L": return "ml";
            default: return unit;
        }
    }

    public static int stockQuantity(String input, String unit, boolean allowZero) {
        requireUnit(unit);
        try {
            BigDecimal amount = new BigDecimal(input.trim());
            if (amount.signum() < 0 || (!allowZero && amount.signum() == 0)) throw new IllegalArgumentException();
            int factor = "dozen".equals(unit) ? 12 : "kg".equals(unit) || "L".equals(unit) ? 1000 : 1;
            int result = amount.multiply(BigDecimal.valueOf(factor)).intValueExact();
            if (result > 2_000_000_000) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Enter " + (allowZero ? "a non-negative" : "a positive")
                    + " quantity that gives a whole number of " + stockUnit(unit) + ".");
        }
    }

    public static String preview(String quantity, String unit) {
        try {
            int count = stockQuantity(quantity, unit, true);
            return "Stock will be saved as " + count + " " + stockUnit(unit) + ".";
        } catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    public static String thresholdLabel(String unit) { return "Low-stock warning threshold (" + stockUnit(unit) + ")"; }

    private static void requireUnit(String unit) {
        if (!OPTIONS.contains(unit)) throw new IllegalArgumentException("Choose a supported unit.");
    }
}

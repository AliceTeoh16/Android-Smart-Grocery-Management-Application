package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.text.StringsKt;

/** Plans shopping additions and combines compatible recipe measurements. */
public final class ChecklistShoppingMerge {
    public static final String SOURCE = "AI Checklist";

    private static final Pattern FULLY_COVERED =
            Pattern.compile("\\s*\\(Fully Covered\\)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MEASUREMENT = Pattern.compile(
            "^(?:(\\d+)\\s+)?(\\d+(?:\\.\\d+)?(?:/\\d+(?:\\.\\d+)?)?|\\.\\d+)\\s*([\\p{L}][\\p{L}\\s.-]*)?$");
    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern TRAILING_DOTS = Pattern.compile("\\.+$");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ChecklistShoppingMerge() {
    }

    @NonNull
    public static Set<String> pendingNames(@NonNull List<ShoppingListItem> items) {
        Set<String> names = new LinkedHashSet<>();
        for (ShoppingListItem item : items) {
            if (!item.isChecked() && item.getQuantityNeeded() > 0) {
                String name = ShoppingVoiceMerge.normalizedName(item.getItemName());
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    public static boolean isPending(@NonNull String itemName, @NonNull Set<String> pendingNames) {
        return pendingNames.contains(ShoppingVoiceMerge.normalizedName(itemName));
    }

    @NonNull
    public static ShoppingListItem merge(@NonNull List<ShoppingListItem> items, @NonNull Addition addition,
            @NonNull String assignedTo) {
        return merge(items, addition, assignedTo, System.currentTimeMillis());
    }

    /** The DAO re-reads the shopping list and applies this plan in a Room transaction. */
    @NonNull
    public static ShoppingListItem merge(@NonNull List<ShoppingListItem> items, @NonNull Addition addition,
            @NonNull String assignedTo, long now) {
        if (StringsKt.isBlank(addition.itemName) || StringsKt.isBlank(assignedTo)) {
            throw new IllegalArgumentException("Choose an item and shopping list.");
        }
        String key = ShoppingVoiceMerge.normalizedName(addition.itemName);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Enter an item name.");
        }

        Amount added = parse(addition.quantityLabel);
        if (added != null) {
            ShoppingListItem match = null;
            Amount previous = null;
            for (ShoppingListItem item : items) {
                if (item.isChecked() || !item.getAssignedTo().equals(assignedTo)
                        || !ShoppingVoiceMerge.normalizedName(item.getItemName()).equals(key)) {
                    continue;
                }
                String label = StringsKt.isBlank(item.getQuantityLabel()) ? Integer.toString(item.getQuantityNeeded())
                        : item.getQuantityLabel();
                Amount amount = parse(label);
                if (amount != null && amount.unit.equals(added.unit)
                        && (match == null || ShoppingVoiceMerge.MERGE_ORDER.compare(item, match) < 0)) {
                    match = item;
                    previous = amount;
                }
            }
            if (match != null) {
                BigDecimal total = previous.value.add(added.value);
                String label = total.stripTrailingZeros().toPlainString() + " " + added.unit;
                String source = match.getSourceItemId() != null ? SOURCE : match.getAddedBy();
                return ShoppingVoiceMerge.reviseItem(match, match.getId(), stockCount(total), label, source, now);
            }
        }

        // Different units require package-size data before they can be combined.
        Matcher firstNumber = FIRST_NUMBER.matcher(addition.quantityLabel);
        BigDecimal count = added != null ? added.value
                : firstNumber.find() ? parseNumber(firstNumber.group()) : null;
        if (count == null) {
            count = BigDecimal.ONE;
        }
        return ShoppingVoiceMerge.createItem(trim(addition.itemName), addition.category, stockCount(count),
                trim(addition.quantityLabel), SOURCE, assignedTo, now, now);
    }

    @Nullable
    private static Amount parse(String label) {
        String cleaned = trim(FULLY_COVERED.matcher(label).replaceAll("")
                .replace("½", " 1/2").replace("¼", " 1/4").replace("¾", " 3/4")
                .replace("⅓", " 1/3").replace("⅔", " 2/3"));
        Matcher measurement = MEASUREMENT.matcher(cleaned);
        if (!measurement.matches()) {
            return null;
        }
        String[] parts = measurement.group(2).split("/");
        BigDecimal fraction = parseNumber(parts[0]);
        if (fraction == null) {
            return null;
        }
        if (parts.length == 2) {
            BigDecimal denominator = parseNumber(parts[1]);
            if (denominator == null || denominator.signum() <= 0) {
                return null;
            }
            fraction = fraction.divide(denominator, MathContext.DECIMAL64);
        }
        String whole = measurement.group(1);
        BigDecimal wholeAmount = whole == null ? null : parseNumber(whole);
        BigDecimal amount = wholeAmount == null ? fraction : wholeAmount.add(fraction);
        if (amount.signum() <= 0) {
            return null;
        }
        String unit = measurement.group(3);
        String raw = unit == null ? "" : trim(unit.toLowerCase(Locale.ROOT));
        raw = SPACES.matcher(TRAILING_DOTS.matcher(raw).replaceAll("")).replaceAll(" ");
        switch (raw) {
            case "kg":
            case "kilogram":
            case "kilograms":
            case "公斤":
            case "千克":
                return new Amount(amount.multiply(BigDecimal.valueOf(1000)), "g");
            case "g":
            case "gram":
            case "grams":
            case "克":
                return new Amount(amount, "g");
            case "l":
            case "liter":
            case "liters":
            case "litre":
            case "litres":
            case "升":
                return new Amount(amount.multiply(BigDecimal.valueOf(1000)), "ml");
            case "ml":
            case "milliliter":
            case "milliliters":
            case "millilitre":
            case "millilitres":
            case "毫升":
                return new Amount(amount, "ml");
            case "":
            case "unit":
            case "units":
            case "pc":
            case "pcs":
            case "piece":
            case "pieces":
            case "个":
            case "颗":
            case "只":
            case "biji":
            case "ekor":
                return new Amount(amount, "pcs");
            case "pack":
            case "packs":
            case "packet":
            case "packets":
            case "包":
            case "bungkus":
            case "pek":
            case "paket":
                return new Amount(amount, "packs");
            case "bottle":
            case "bottles":
            case "瓶":
            case "botol":
                return new Amount(amount, "bottles");
            case "tbsp":
            case "tablespoon":
            case "tablespoons":
            case "汤匙":
                return new Amount(amount, "tbsp");
            case "tsp":
            case "teaspoon":
            case "teaspoons":
            case "茶匙":
                return new Amount(amount, "tsp");
            case "clove":
            case "cloves":
            case "瓣":
            case "ulas":
                return new Amount(amount, "cloves");
            case "stalk":
            case "stalks":
            case "根":
            case "棵":
            case "条":
            case "batang":
                return new Amount(amount, "stalks");
            case "slice":
            case "slices":
            case "片":
            case "keping":
                return new Amount(amount, "slices");
            case "cup":
            case "cups":
            case "杯":
                return new Amount(amount, "cups");
            default:
                return new Amount(amount, raw);
        }
    }

    @Nullable
    private static BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int stockCount(BigDecimal value) {
        BigDecimal count = value.setScale(0, RoundingMode.CEILING).max(BigDecimal.ONE);
        if (count.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("The combined quantity is too large.");
        }
        return count.intValueExact();
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }

    private static final class Amount {
        private final BigDecimal value;
        private final String unit;

        private Amount(BigDecimal value, String unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    public static final class Addition {
        private final String itemName;
        private final String category;
        private final String quantityLabel;

        public Addition(@NonNull String itemName, @NonNull String category, @NonNull String quantityLabel) {
            this.itemName = itemName;
            this.category = category;
            this.quantityLabel = quantityLabel;
        }

        @NonNull
        public String getItemName() { return itemName; }

        @NonNull
        public String getCategory() { return category; }

        @NonNull
        public String getQuantityLabel() { return quantityLabel; }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Addition)) {
                return false;
            }
            Addition addition = (Addition) other;
            return itemName.equals(addition.itemName) && category.equals(addition.category)
                    && quantityLabel.equals(addition.quantityLabel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemName, category, quantityLabel);
        }
    }
}

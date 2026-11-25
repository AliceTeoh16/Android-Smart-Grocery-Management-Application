 package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.entity.ShoppingListItem;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.text.StringsKt;

/** Plans a voice addition for both the review preview and the transactional shopping write. */
public final class ShoppingVoiceMerge {
    public static final String VOICE_SOURCE = "Voice";
    public static final List<String> assignees = Collections.unmodifiableList(Arrays.asList("Me", "Household"));
    static final Comparator<ShoppingListItem> MERGE_ORDER = Comparator
            .comparing((ShoppingListItem item) -> item.getSourceItemId() != null)
            .thenComparingInt(ShoppingListItem::getId);

    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
    private static final Pattern NON_NAME = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern UNIT_LABEL = Pattern.compile("^(\\d+)\\s*([\\p{L}]+)?$");
    private static final Map<String, String> SINGULAR_NAMES = new HashMap<>();

    static {
        SINGULAR_NAMES.put("eggs", "egg");
        SINGULAR_NAMES.put("apples", "apple");
        SINGULAR_NAMES.put("bananas", "banana");
        SINGULAR_NAMES.put("tomatoes", "tomato");
        SINGULAR_NAMES.put("potatoes", "potato");
        SINGULAR_NAMES.put("carrots", "carrot");
        SINGULAR_NAMES.put("onions", "onion");
        SINGULAR_NAMES.put("peppers", "pepper");
        SINGULAR_NAMES.put("wings", "wing");
        SINGULAR_NAMES.put("yogurts", "yogurt");
        SINGULAR_NAMES.put("yoghurts", "yogurt");
        SINGULAR_NAMES.put("yoghurt", "yogurt");
        SINGULAR_NAMES.put("oranges", "orange");
        SINGULAR_NAMES.put("lemons", "lemon");
        SINGULAR_NAMES.put("mushrooms", "mushroom");
    }

    private ShoppingVoiceMerge() {
    }

    @NonNull
    public static ShoppingListItem merge(@NonNull List<ShoppingListItem> items, @NonNull VoiceCommand command,
            @NonNull String assignedTo) {
        if (!assignees.contains(assignedTo)) {
            throw new IllegalArgumentException("Choose Me only or Household.");
        }
        if (command.getAction() != VoiceAction.ADD || command.getQuantity() <= 0
                || StringsKt.isBlank(command.getItemName())) {
            throw new IllegalArgumentException("Review all item names and quantities before adding.");
        }
        String name = normalizedName(command.getItemName());
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Enter an item name.");
        }

        ShoppingListItem existing = null;
        String unit = "";
        for (ShoppingListItem item : items) {
            if (item.isChecked() || !item.getAssignedTo().equals(assignedTo)
                    || !normalizedName(item.getItemName()).equals(name)) {
                continue;
            }
            String candidateUnit = countUnit(item);
            if (candidateUnit != null && (existing == null || MERGE_ORDER.compare(item, existing) < 0)) {
                unit = candidateUnit;
                existing = item;
            }
        }
        if (existing == null) {
            return createItem(trim(command.getItemName()), GroceryCategories.suggest(command.getItemName()),
                    command.getQuantity(), "", VOICE_SOURCE, assignedTo, System.currentTimeMillis(),
                    System.currentTimeMillis());
        }

        long total = (long) existing.getQuantityNeeded() + command.getQuantity();
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The combined quantity is too large. Edit your command.");
        }
        String label = unit.isEmpty() ? "" : total + " " + unit;
        // Keep voice-confirmed quantities from being reset by automatic low-stock suggestions.
        String addedBy = existing.getSourceItemId() != null ? VOICE_SOURCE : existing.getAddedBy();
        return reviseItem(existing, existing.getId(), (int) total, label, addedBy, System.currentTimeMillis());
    }

    @NonNull
    public static List<Preview> preview(@NonNull List<ShoppingListItem> items, @NonNull List<VoiceCommand> commands,
            @NonNull String assignedTo) {
        List<ShoppingListItem> pending = new ArrayList<>(items);
        List<Preview> previews = new ArrayList<>();
        for (VoiceCommand command : commands) {
            if (command.getAction() != VoiceAction.ADD) {
                continue;
            }
            ShoppingListItem result = merge(pending, command, assignedTo);
            int existingIndex = indexOf(pending, result.getId());
            Integer previous = null;
            if (existingIndex >= 0) {
                previous = pending.get(existingIndex).getQuantityNeeded();
                pending.set(existingIndex, result);
            } else {
                ShoppingListItem staged = result.getId() != 0 ? result
                        : reviseItem(result, -previews.size() - 1, result.getQuantityNeeded(),
                                result.getQuantityLabel(), result.getAddedBy(), result.getUpdatedAt());
                pending.add(staged);
            }
            previews.add(new Preview(result.getItemName(), command.getQuantity(), previous, result.getQuantityNeeded()));
        }
        return previews;
    }

    @NonNull
    public static String normalizedName(@NonNull String value) {
        String plain = PARENTHETICAL.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replaceAll(" ").toLowerCase(Locale.ROOT);
        plain = WHITESPACE.matcher(trim(NON_NAME.matcher(plain).replaceAll(" "))).replaceAll(" ");
        String alias = VoiceParser.normalizeItemName(plain);
        // Whole-word aliases keep names such as egg and eggplant distinct.
        StringBuilder result = new StringBuilder(alias.length());
        String[] words = alias.split(" ", -1);
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(' ');
            }
            result.append(SINGULAR_NAMES.getOrDefault(words[i], words[i]));
        }
        return result.toString();
    }

    private static int indexOf(List<ShoppingListItem> pending, int id) {
        if (id != 0) {
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).getId() == id) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Voice counts can be added only to item and package counts. */
    @Nullable
    private static String countUnit(ShoppingListItem item) {
        if (StringsKt.isBlank(item.getQuantityLabel())) {
            return "";
        }
        Matcher label = UNIT_LABEL.matcher(trim(item.getQuantityLabel()).toLowerCase(Locale.ROOT));
        if (!label.matches()) {
            return null;
        }
        Long count = StringsKt.toLongOrNull(label.group(1));
        if (count == null || count != item.getQuantityNeeded()) {
            return null;
        }
        String unit = label.group(2);
        switch (unit != null ? unit : "") {
            case "":
                return "";
            case "pc":
            case "pcs":
            case "piece":
            case "pieces":
            case "个":
            case "颗":
            case "只":
            case "biji":
            case "ekor":
                return "pcs";
            case "bottle":
            case "bottles":
            case "瓶":
            case "botol":
                return "bottles";
            case "pack":
            case "packs":
            case "packet":
            case "packets":
            case "包":
            case "bungkus":
            case "pek":
            case "paket":
                return "packs";
            case "carton":
            case "cartons":
                return "cartons";
            case "box":
            case "boxes":
            case "盒":
            case "箱":
            case "kotak":
                return "boxes";
            case "can":
            case "cans":
            case "罐":
            case "tin":
                return "cans";
            case "bag":
            case "bags":
            case "袋":
                return "bags";
            case "stalk":
            case "stalks":
            case "根":
            case "棵":
            case "batang":
                return "stalks";
            case "clove":
            case "cloves":
            case "瓣":
            case "ulas":
                return "cloves";
            case "slice":
            case "slices":
            case "片":
            case "keping":
                return "slices";
            default:
                return null;
        }
    }

    static ShoppingListItem createItem(String name, String category, int count, String quantityLabel,
            String addedBy, String assignedTo, long createdAt, long updatedAt) {
        return new ShoppingListItem(0, null, name, category, count, quantityLabel, 0.0, addedBy, assignedTo,
                false, createdAt, updatedAt, null, "", null, null, null, null, null);
    }

    static ShoppingListItem reviseItem(ShoppingListItem item, int id, int count, String quantityLabel,
            String addedBy, long updatedAt) {
        return item.copy(id, item.getSourceItemId(), item.getItemName(), item.getCategory(), count,
                quantityLabel, item.getEstimatedUnitPrice(), addedBy, item.getAssignedTo(), item.isChecked(),
                item.getCreatedAt(), updatedAt, item.getSubcategory(), item.getUserId(), item.getHouseholdId(),
                item.getAddedByName(), item.getLastModifiedByName(), item.getLastModifiedAction(), item.getLastModifiedAt());
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }

    public static final class Preview {
        private final String itemName;
        private final int added;
        private final Integer previous;
        private final int total;

        public Preview(@NonNull String itemName, int added, @Nullable Integer previous, int total) {
            this.itemName = itemName;
            this.added = added;
            this.previous = previous;
            this.total = total;
        }

        @NonNull
        public String getItemName() { return itemName; }

        public int getAdded() { return added; }

        @Nullable
        public Integer getPrevious() { return previous; }

        public int getTotal() { return total; }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Preview)) {
                return false;
            }
            Preview preview = (Preview) other;
            return added == preview.added && total == preview.total && itemName.equals(preview.itemName)
                    && Objects.equals(previous, preview.previous);
        }

        @Override
        public int hashCode() {
            return ((itemName.hashCode() * 31 + added) * 31 + Objects.hashCode(previous)) * 31 + total;
        }
    }
}

package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.text.StringsKt;

/** Parses grocery names and whole item counts from OCR text. */
public final class IngredientTextParser {
    public static final List<String> categories = Collections.unmodifiableList(Arrays.asList(
            "Dairy", "Fruits & Vegetables", "Meat & Seafood", "Bakery", "Pantry", "Beverages", "Snacks", "Other"));

    private static final Pattern IGNORED = pattern("^(?:nutrition|nutritional|nutrition facts|energy|calories|protein|carbohydrate|carbs|fat|saturated|sugars|sodium|cholesterol|fibre|fiber|servings?|serving size|ingredients?[:：]|allergen|contains[:：]|best before|use by|expiry|exp[:： ]|manufactur|packed on|product of|made in|net (?:weight|volume|content)|nett? wt|total|subtotal|tax|gst|sst|change|cash|receipt|invoice|thank you|www\\.|https?://|barcode|sku|product label test|berat bersih|isi padu|tarikh|jumlah|营养|營養|配料|成分|净重|淨重|净含量|保质期|有效期|生产日期|總計|总计)");
    private static final String UNITS = "(?:(?:pieces?|pcs?|packets?|packs?|bottles?|cartons?|boxes|box|bags?|cans?|biji|botol|kotak|bungkus|pek|tin)(?![A-Za-z])|个|個|颗|顆|枚|瓶|盒|包|袋|罐|只|支)";
    private static final Pattern MEASUREMENT = pattern("\\d+(?:[.,]\\d+)?\\s*(?:kg|mg|g|ml|l|litres?|liters?|grams?|kcal|kj|%|公斤|千克|克|毫升|升)(?![\\p{L}])");
    private static final Pattern SEPARATE_COUNT = pattern("^(?:(?:qty|quantity|kuantiti|数量|數量)\\s*[:：x×]?\\s*)?(\\d+)\\s*" + UNITS + "?$");
    private static final Pattern PREFIX = pattern("^(\\d+)\\s*(?:[x×]\\s*)?(?:" + UNITS + "\\s*(?:of\\s+)?)?(.+)$");
    private static final Pattern SUFFIX = pattern("^(.+?)\\s*(?:[x×]\\s*|(?:qty|quantity|kuantiti|数量|數量)\\s*[:：]?\\s*)(\\d+)\\s*(?:" + UNITS + ")?$");
    private static final Pattern COUNTED_SUFFIX = pattern("^(.+?)\\s+(\\d+)\\s*(?:" + UNITS + ")?$");
    private static final Pattern COMPACT_SUFFIX = pattern("^(.+?)(\\d+)\\s*" + UNITS + "$");
    private static final Pattern INVALID_COUNT = pattern("(?:[x×]\\s*|\\bqty\\s*[:：]?\\s*)-?\\d+[.,]\\d+|[x×]\\s*-\\d+");
    private static final Pattern MONEY_OR_DATE =
            pattern("^(?:RM|MYR|\\$)\\s*\\d|^\\d+[.,]\\d{2}$|^\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}$");
    private static final Pattern TRAILING_MONEY = pattern("\\s+(?:RM|MYR|\\$)\\s*\\d+(?:[.,]\\d{2})?\\s*$");
    private static final Pattern TRAILING_PRICE = Pattern.compile("\\s+\\d+[.,]\\d{2}\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s{2,}");
    private static final Pattern BARCODE = Pattern.compile("\\d{6,}");

    private static final Map<String, Pattern> CATEGORY_PATTERNS = categoryPatterns();

    private IngredientTextParser() {
    }

    @NonNull
    public static List<IngredientTextCandidate> parse(@NonNull List<String> rawLines) {
        List<IngredientTextCandidate> candidates = new ArrayList<>();
        for (String raw : rawLines.subList(0, Math.min(rawLines.size(), 400))) {
            String line = trim(Normalizer.normalize(raw, Normalizer.Form.NFKC));
            int start = 0;
            while (start < line.length() && "•·-*".indexOf(line.charAt(start)) >= 0) {
                start++;
            }
            line = trim(line.substring(start));
            if (line.isEmpty() || line.length() > 140 || IGNORED.matcher(line).find()
                    || MONEY_OR_DATE.matcher(line).find()) {
                continue;
            }
            Matcher standalone = SEPARATE_COUNT.matcher(line);
            if (standalone.matches()) {
                updateLastQuantity(candidates, StringsKt.toIntOrNull(standalone.group(1)));
                continue;
            }
            IngredientTextCandidate candidate = parseLine(line, raw);
            if (candidate != null) {
                addCandidate(candidates, candidate);
            }
        }
        return candidates;
    }

    private static IngredientTextCandidate parseLine(String line, String raw) {
        line = TRAILING_MONEY.matcher(line).replaceAll("");
        line = TRAILING_PRICE.matcher(line).replaceAll("");
        line = WHITESPACE.matcher(trim(MEASUREMENT.matcher(line).replaceAll(""))).replaceAll(" ");
        if (!line.chars().anyMatch(Character::isLetter)) {
            return null;
        }

        Integer quantity = null;
        boolean countMentioned = true;
        Matcher prefix = PREFIX.matcher(line);
        Matcher suffix = SUFFIX.matcher(line);
        Matcher countedSuffix = COUNTED_SUFFIX.matcher(line);
        Matcher compactSuffix = COMPACT_SUFFIX.matcher(line);
        if (prefix.matches()) {
            quantity = StringsKt.toIntOrNull(prefix.group(1));
            line = prefix.group(2);
        } else if (suffix.matches()) {
            quantity = StringsKt.toIntOrNull(suffix.group(2));
            line = suffix.group(1);
        } else if (countedSuffix.matches()) {
            quantity = StringsKt.toIntOrNull(countedSuffix.group(2));
            line = countedSuffix.group(1);
        } else if (compactSuffix.matches()) {
            quantity = StringsKt.toIntOrNull(compactSuffix.group(2));
            line = compactSuffix.group(1);
        } else {
            countMentioned = false;
        }

        String name = trim(line);
        int end = name.length();
        while (end > 0 && ":：-×".indexOf(name.charAt(end - 1)) >= 0) {
            end--;
        }
        name = limit(trim(name.substring(0, end)), 100);
        if ((name.length() < 2 && !hasChineseCharacter(name))
                || !name.chars().anyMatch(Character::isLetter) || BARCODE.matcher(name).find()) {
            return null;
        }
        String category = categoryFor(name);
        boolean detected = validCount(quantity);
        boolean selected = !"Other".equals(category) && (!countMentioned || detected)
                && !INVALID_COUNT.matcher(name).find();
        return new IngredientTextCandidate(name, category, detected ? quantity : 1, detected,
                selected, limit(trim(raw), 140));
    }

    private static void updateLastQuantity(List<IngredientTextCandidate> candidates, Integer quantity) {
        if (validCount(quantity) && !candidates.isEmpty()) {
            int index = candidates.size() - 1;
            IngredientTextCandidate prior = candidates.get(index);
            candidates.set(index, new IngredientTextCandidate(prior.getName(), prior.getCategory(),
                    quantity, true, prior.getSelected(), prior.getSource()));
        }
    }

    private static void addCandidate(List<IngredientTextCandidate> candidates, IngredientTextCandidate candidate) {
        String name = candidate.getName().toLowerCase(Locale.ROOT);
        for (int i = 0; i < candidates.size(); i++) {
            IngredientTextCandidate old = candidates.get(i);
            if (old.getName().toLowerCase(Locale.ROOT).equals(name) && (old.getQuantity() == candidate.getQuantity()
                    || !old.getQuantityDetected() || !candidate.getQuantityDetected())) {
                if (candidate.getQuantityDetected()) {
                    candidates.set(i, candidate);
                }
                return;
            }
        }
        if (candidates.size() < 50) {
            candidates.add(candidate);
        }
    }

    private static String categoryFor(String name) {
        for (Map.Entry<String, Pattern> entry : CATEGORY_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(name).find()) {
                return entry.getKey();
            }
        }
        return "Other";
    }

    private static Map<String, Pattern> categoryPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("Dairy", foodPattern("milk|eggs?|yog[hu]?urt|cheese|butter|cream|susu|telur|keju|牛奶|鸡蛋|雞蛋|酸奶|奶酪|黄油|黃油"));
        patterns.put("Fruits & Vegetables", foodPattern("apples?|bananas?|oranges?|tomato(?:es)?|carrots?|onions?|garlic|potato(?:es)?|lettuce|spinach|broccoli|cabbage|berries|avocados?|buah|pisang|epal|tomato|lobak|bawang|kentang|sayur|苹果|蘋果|香蕉|橙|番茄|西红柿|胡萝卜|胡蘿蔔|洋葱|洋蔥|土豆|马铃薯|蒜|蔬菜"));
        patterns.put("Meat & Seafood", foodPattern("chicken|beef|pork|lamb|fish|salmon|tuna|prawns?|shrimp|seafood|ayam|daging|ikan|udang|鸡肉|雞肉|牛肉|猪肉|豬肉|羊肉|鱼|魚|虾|蝦"));
        patterns.put("Bakery", foodPattern("bread|buns?|bagels?|croissants?|roti|面包|麵包|馒头|饅頭"));
        patterns.put("Pantry", foodPattern("rice|pasta|noodles?|flour|sugar|salt|oil|sauce|beans?|lentils?|oats?|beras|mi|mee|tepung|gula|garam|minyak|kicap|大米|米饭|面条|麵條|面粉|糖|盐|鹽|食用油|酱油|醬油"));
        patterns.put("Beverages", foodPattern("juices?|coffee|tea|water|soda|jus|kopi|teh|minuman|果汁|咖啡|茶|饮料|飲料"));
        patterns.put("Snacks", foodPattern("biscuits?|cookies?|crackers?|chips|chocolates?|nuts?|biskut|coklat|kacang|饼干|餅乾|巧克力|薯片|坚果|堅果"));
        return patterns;
    }

    private static Pattern foodPattern(String words) {
        StringBuilder regex = new StringBuilder();
        for (String word : words.split("\\|")) {
            if (regex.length() > 0) {
                regex.append('|');
            }
            if (hasChineseCharacter(word)) {
                regex.append(word);
            } else {
                regex.append("\\b(?:").append(word).append(")\\b");
            }
        }
        return pattern(regex.toString());
    }

    private static boolean hasChineseCharacter(String value) {
        return value.chars().anyMatch(c -> c >= '\u4e00' && c <= '\u9fff');
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean validCount(Integer count) {
        return count != null && count >= 1 && count <= 999;
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }

    private static String limit(String value, int length) {
        return value.substring(0, Math.min(length, value.length()));
    }
}

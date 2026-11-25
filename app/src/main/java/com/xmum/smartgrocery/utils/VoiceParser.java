package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.text.StringsKt;

/** Parses grocery voice commands in English, Malay, and Mandarin. */
public final class VoiceParser {
    private static final Set<String> ADD_ACTIONS = new HashSet<>(Arrays.asList(
            "add", "buy", "purchase", "plus", "tambah", "tambahkan",
            "beli", "belikan", "membeli", "menambah", "masukkan"));
    private static final Set<String> DEDUCT_ACTIONS = new HashSet<>(Arrays.asList(
            "use", "used", "deduct", "remove", "minus", "eat", "ate", "consumed",
            "guna", "gunakan", "pakai", "kurangkan", "tolak", "buang", "makan"));
    private static final Pattern ACTION = Pattern.compile(
            "^(?:(?:please|i|have|has|had|already|just|want|to|sila|tolong|saya|aku|nak|mahu|ingin|dah|sudah|telah|baru|hendak|boleh)\\s+)*"
                    + "(add|buy|purchase|plus|tambah|tambahkan|beli|belikan|membeli|menambah|masukkan|use|used|deduct|remove|minus|eat|ate|consumed|guna|gunakan|pakai|kurangkan|tolak|buang|makan)\\b\\s*");
    private static final Pattern RECIPE_WORD = Pattern.compile("\\b(?:suggest|recommend|recipe|cook|meal|dinner|lunch|breakfast|cadangkan|resipi|resepi|masak)\\b");
    private static final String[] COUNT_WORDS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"
    };
    private static final Pattern[] WORD_COUNT_PATTERNS = countPatterns();
    private static final Pattern INVALID_COUNT = Pattern.compile(
            "(?:-\\s*\\d|\\d[.,]\\d|\\b\\d+\\s+(?:point|perpuluhan|koma)\\s+\\d+\\b"
                    + "|\\bnegatif\\b|\\b(?:setengah|separuh)\\b)");
    private static final Pattern SEPARATOR = Pattern.compile(
            "\\s*[,;]\\s*|\\s+(?:and|dan|serta|kemudian|lepas itu|selepas itu)(?:\\s+|$)");
    private static final Pattern SHOPPING_DESTINATION = Pattern.compile(
            "\\s+(?:to|in)\\s+(?:my\\s+)?shopping list$|\\s+shopping list$");
    private static final Pattern MALAY_DESTINATION = Pattern.compile(
            "\\s+(?:ke(?: dalam)?|dalam)\\s+(?:senarai (?:beli-belah|membeli-belah|belian|belanja)|inventori|stok)$");
    private static final Pattern LEADING_COUNT = Pattern.compile("^(\\d+|a|an)\\s+(.+)$");
    private static final Pattern LEADING_UNIT = Pattern.compile(
            "^(?:bottles?|cartons?|packs?|packets?|bags?|cans?|pieces?|pcs|boxes?|"
                    + MalayVoiceSupport.classifiers + ")\\s+(?:(?:of|of the)\\s+)?");
    private static final Pattern STRAY_COUNT = Pattern.compile("\\b\\d+\\b");

    private static final List<String> CHINESE_ADD = Arrays.asList("添加", "加入", "新增", "购买", "买", "加");
    private static final List<String> CHINESE_DEDUCT = Arrays.asList("使用", "用掉", "用了", "扣除", "减少", "吃掉", "吃了", "消耗", "减去", "减", "用", "吃");
    private static final String CHINESE_ACTIONS = "添加|加入|新增|购买|买|加|使用|用掉|用了|扣除|减少|吃掉|吃了|消耗|减去|减|用|吃";
    private static final String CHINESE_NUMBER = "(?:[0-9]+|[零〇一二两三四五六七八九十百千万]+)";
    private static final String CHINESE_UNIT = "[个颗粒只盒瓶包袋斤份条块罐杯支枚棵根片碗箱]";
    private static final Pattern CHINESE_QUANTITY = Pattern.compile("^" + CHINESE_NUMBER);
    private static final Pattern CHINESE_LEADING_UNIT = Pattern.compile("^" + CHINESE_UNIT);
    private static final Pattern CHINESE_COUNT_AND_UNIT = Pattern.compile(CHINESE_NUMBER + CHINESE_UNIT);
    private static final Pattern CHINESE_CONTROL = Pattern.compile("^(?:" + CHINESE_ACTIONS + ")");
    private static final Pattern CHINESE_TRAILING_ACTION = Pattern.compile("(?:" + CHINESE_ACTIONS + ")(?:了)?$");
    private static final Pattern CHINESE_NEXT_ACTION = Pattern.compile("(?:" + CHINESE_ACTIONS + ")(?:了)?" + CHINESE_NUMBER);
    private static final Pattern CHINESE_SEPARATOR = Pattern.compile(
            "(?:[，,、；;。]|以及|还有|然后|接着|并且|和|跟|及|再(?=(?:" + CHINESE_ACTIONS + ")))+");
    private static final Pattern CHINESE_PREFIX = Pattern.compile("^(?:请|帮我|我|已经|刚刚|刚|想要|想|要|再|也|把|了)+");
    private static final Pattern CHINESE_DESTINATION = Pattern.compile(
            "(?:添加到|加入|放到|到|进)?(?:我的)?(?:购物清单|购物列表|库存)$");
    private static final Pattern CHINESE_RECIPE = Pattern.compile("食谱|菜谱|料理|推荐|建议|煮|做饭|晚餐|午餐|早餐");
    private static final Pattern CHINESE_INVALID_COUNT = Pattern.compile(
            "[-－负]\\s*[0-9零〇一二两三四五六七八九十]|[0-9零〇一二两三四五六七八九十]"
                    + "[.点][0-9零〇一二两三四五六七八九十]|半(?:个|瓶|只|盒|包|斤)");
    private static final Pattern CHINESE_STRAY_COUNT =
            Pattern.compile("^[0-9零〇一二两三四五六七八九十百千万]+$|" + CHINESE_NUMBER + CHINESE_UNIT);
    private static final Pattern CHINESE_TENS = Pattern.compile("[一二两三四五六七八九]?十[一二三四五六七八九]?");
    private static final Pattern CHINESE_NUMBERED_FOOD = Pattern.compile("^(?:三文鱼|四季豆|九层塔)");

    private VoiceParser() {
    }

    @NonNull
    public static VoiceCommand parse(@Nullable String text) {
        return parseAll(text).get(0);
    }

    @NonNull
    public static List<VoiceCommand> parseAll(@Nullable String text) {
        if (text == null || StringsKt.isBlank(text)) {
            return unknown("");
        }
        String cleanText = trim(Normalizer.normalize(text, Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT);
        if (cleanText.chars().anyMatch(c -> c >= '\u4e00' && c <= '\u9fff')) {
            return parseChinese(cleanText);
        }
        if (!ACTION.matcher(cleanText).find() && RECIPE_WORD.matcher(cleanText).find()) {
            return Collections.singletonList(new VoiceCommand(VoiceAction.RECIPE, 0, cleanText));
        }

        String normalized = MalayVoiceSupport.normalizeCounts(stripEnd(cleanText, ".!?"));
        for (int i = 0; i < WORD_COUNT_PATTERNS.length; i++) {
            normalized = WORD_COUNT_PATTERNS[i].matcher(normalized).replaceAll(Integer.toString(i));
        }
        if (INVALID_COUNT.matcher(normalized).find()) {
            return unknown(cleanText);
        }

        List<VoiceCommand> commands = new ArrayList<>();
        VoiceAction previousAction = VoiceAction.UNKNOWN;
        for (String part : SEPARATOR.split(normalized, -1)) {
            String remaining = trim(part);
            Matcher actionMatch = ACTION.matcher(remaining);
            VoiceAction action = previousAction;
            if (actionMatch.find()) {
                action = actionFor(actionMatch.group(1), previousAction, ADD_ACTIONS, DEDUCT_ACTIONS);
                remaining = trim(remaining.substring(actionMatch.end()));
            }
            previousAction = action;
            remaining = trim(SHOPPING_DESTINATION.matcher(remaining).replaceAll(""));
            remaining = trim(MALAY_DESTINATION.matcher(remaining).replaceAll(""));

            Matcher count = LEADING_COUNT.matcher(remaining);
            int quantity = 1;
            if (count.matches()) {
                String raw = count.group(1);
                if (!"a".equals(raw) && !"an".equals(raw)) {
                    quantity = integerOrZero(raw);
                }
                remaining = count.group(2);
            }
            String name = normalizeItemName(LEADING_UNIT.matcher(remaining).replaceAll(""));
            // Reject an incomplete clause rather than applying only the other items.
            if (action == VoiceAction.UNKNOWN || name.isEmpty()
                    || name.chars().allMatch(Character::isDigit) || STRAY_COUNT.matcher(name).find()) {
                commands.add(new VoiceCommand(VoiceAction.UNKNOWN, 0, part));
            } else {
                commands.add(new VoiceCommand(action, quantity, name));
            }
        }
        return commands;
    }

    private static List<VoiceCommand> parseChinese(String text) {
        String cleanText = simplifyChinese(text).replaceAll("\\s+", "");
        cleanText = CHINESE_DESTINATION.matcher(stripEnd(cleanText, "。.!！?？")).replaceAll("");
        String firstClause = CHINESE_PREFIX.matcher(cleanText).replaceAll("");
        if (!CHINESE_CONTROL.matcher(firstClause).find() && CHINESE_RECIPE.matcher(cleanText).find()) {
            return Collections.singletonList(new VoiceCommand(VoiceAction.RECIPE, 0, cleanText));
        }
        if (CHINESE_INVALID_COUNT.matcher(cleanText).find()) {
            return unknown(cleanText);
        }

        List<VoiceCommand> commands = new ArrayList<>();
        VoiceAction previousAction = VoiceAction.UNKNOWN;
        for (String segment : CHINESE_SEPARATOR.split(cleanText, -1)) {
            for (String part : splitChineseItems(segment)) {
                String clause = CHINESE_PREFIX.matcher(part).replaceAll("");
                String keyword = chineseActionAtStart(clause);
                VoiceAction action = actionFor(keyword, previousAction, CHINESE_ADD, CHINESE_DEDUCT);
                previousAction = action;
                String remaining = CHINESE_PREFIX.matcher(clause.substring(keyword.length())).replaceAll("");
                if (remaining.endsWith("了")) {
                    remaining = remaining.substring(0, remaining.length() - 1);
                }

                Matcher count = CHINESE_QUANTITY.matcher(remaining);
                int quantity = 1;
                if (!CHINESE_NUMBERED_FOOD.matcher(remaining).find() && count.find()) {
                    quantity = parseChineseQuantity(count.group());
                    remaining = CHINESE_LEADING_UNIT.matcher(remaining.substring(count.end())).replaceAll("");
                }
                String name = trim(remaining);
                if (action == VoiceAction.UNKNOWN || name.isEmpty() || CHINESE_STRAY_COUNT.matcher(name).find()) {
                    commands.add(new VoiceCommand(VoiceAction.UNKNOWN, 0, part));
                } else {
                    commands.add(new VoiceCommand(action, quantity, normalizeItemName(name)));
                }
            }
        }
        return commands;
    }

    private static List<String> splitChineseItems(String part) {
        // Speech recognition can omit punctuation between items with explicit counts.
        String clause = CHINESE_PREFIX.matcher(part).replaceAll("");
        String action = chineseActionAtStart(clause);
        String body = CHINESE_PREFIX.matcher(clause.substring(action.length())).replaceAll("");
        Set<Integer> cuts = new TreeSet<>();
        Matcher counts = CHINESE_COUNT_AND_UNIT.matcher(body);
        while (counts.find()) {
            int offset = counts.start();
            if (offset > 0 && Character.isLetter(body.charAt(offset - 1))
                    && !CHINESE_TRAILING_ACTION.matcher(body.substring(0, offset)).find()) {
                cuts.add(offset);
            }
        }
        Matcher actions = CHINESE_NEXT_ACTION.matcher(body);
        while (actions.find()) {
            if (actions.start() > 0) {
                cuts.add(actions.start());
            }
        }

        List<String> items = new ArrayList<>();
        int start = 0;
        for (int cut : cuts) {
            items.add((start == 0 ? action : "") + body.substring(start, cut));
            start = cut;
        }
        items.add((start == 0 ? action : "") + body.substring(start));
        return items;
    }

    private static String chineseActionAtStart(String clause) {
        for (String keyword : CHINESE_ADD) {
            if (clause.startsWith(keyword)) {
                return keyword;
            }
        }
        for (String keyword : CHINESE_DEDUCT) {
            if (clause.startsWith(keyword)) {
                return keyword;
            }
        }
        return "";
    }

    private static VoiceAction actionFor(String keyword, VoiceAction fallback,
            java.util.Collection<String> addWords, java.util.Collection<String> deductWords) {
        if (addWords.contains(keyword)) {
            return VoiceAction.ADD;
        }
        return deductWords.contains(keyword) ? VoiceAction.DEDUCT : fallback;
    }

    private static String simplifyChinese(String text) {
        String traditional = "購買雞蛋兩個顆減條塊魚蘿蔔麵鹽醬飯庫單葉";
        String simplified = "购买鸡蛋两个颗减条块鱼萝卜面盐酱饭库单叶";
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char letter = text.charAt(i);
            int index = traditional.indexOf(letter);
            result.append(index < 0 ? letter : simplified.charAt(index));
        }
        return result.toString();
    }

    private static int parseChineseQuantity(String text) {
        if (text.chars().allMatch(Character::isDigit)) {
            return integerOrZero(text);
        }
        if (text.length() == 1) {
            return text.charAt(0) == '十' ? 10 : chineseDigit(text.charAt(0));
        }
        if (CHINESE_TENS.matcher(text).matches()) {
            int separator = text.indexOf('十');
            int tens = separator == 0 ? 1 : chineseDigit(text.charAt(0));
            int ones = separator + 1 == text.length() ? 0 : chineseDigit(text.charAt(separator + 1));
            return tens * 10 + ones;
        }
        return 0;
    }

    private static int chineseDigit(char digit) {
        if (digit == '两') {
            return 2;
        }
        return Math.max(0, "零一二三四五六七八九".indexOf(digit));
    }

    @NonNull
    static String normalizeItemName(@NonNull String input) {
        String value = trim(input);
        switch (value) {
            case "susu":
            case "牛奶":
                return "milk";
            case "telur":
            case "鸡蛋":
            case "蛋":
                return "eggs";
            case "sayap ayam":
            case "鸡翅":
                return "chicken wing";
            case "roti":
            case "面包":
                return "bread";
            case "daging lembu":
            case "牛肉":
                return "beef";
            case "daging ayam":
            case "ayam":
                return "chicken";
            case "beras":
            case "大米":
            case "米":
                return "rice";
            case "minyak masak":
            case "食用油":
                return "cooking oil";
            case "kicap":
            case "酱油":
                return "soy sauce";
            case "garam":
            case "盐":
                return "salt";
            case "gula":
                return "sugar";
            case "lobak merah":
            case "胡萝卜":
            case "萝卜":
                return "carrot";
            case "kentang":
            case "土豆":
            case "马铃薯":
                return "potato";
            case "bawang putih":
                return "garlic";
            case "bawang":
                return "onion";
            case "mi":
            case "mee":
            case "面条":
                return "noodles";
            case "ikan":
            case "鱼":
            case "鱼肉":
                return "fish";
            case "yogurt greek":
            case "希腊酸奶":
                return "greek yogurt";
            case "三文鱼":
                return "salmon";
            case "酸奶":
                return "yogurt";
            case "番茄":
            case "西红柿":
                return "tomato";
            case "鳄梨":
            case "牛油果":
                return "avocado";
            case "肉":
                return "meat";
            case "蔬菜":
            case "青菜":
                return "vegetable";
            default:
                return value;
        }
    }

    private static Pattern[] countPatterns() {
        Pattern[] patterns = new Pattern[COUNT_WORDS.length];
        for (int i = 0; i < COUNT_WORDS.length; i++) {
            patterns[i] = Pattern.compile("\\b" + COUNT_WORDS[i] + "\\b");
        }
        return patterns;
    }

    private static String stripEnd(String value, String punctuation) {
        int end = value.length();
        while (end > 0 && punctuation.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String trim(String value) {
        return StringsKt.trim(value).toString();
    }

    private static int integerOrZero(String value) {
        Integer integer = StringsKt.toIntOrNull(value);
        return integer != null ? integer : 0;
    }

    private static List<VoiceCommand> unknown(String value) {
        return Collections.singletonList(new VoiceCommand(VoiceAction.UNKNOWN, 0, value));
    }
}

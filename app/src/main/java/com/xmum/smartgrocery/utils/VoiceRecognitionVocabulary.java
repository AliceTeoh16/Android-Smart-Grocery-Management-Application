package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import kotlin.text.StringsKt;

/** Provides hints for the device's speech recognition service. */
public final class VoiceRecognitionVocabulary {
    private static final Pattern NAME_SEPARATOR = Pattern.compile("[()（）·/]");
    private static final int MAX_HINTS = 100;
    private static final int MAX_HINT_LENGTH = 80;

    private VoiceRecognitionVocabulary() {
    }

    @NonNull
    public static ArrayList<String> build(@NonNull List<String> itemNames, @NonNull VoiceLanguage language) {
        List<String> defaults = new ArrayList<>(common(language));
        defaults.addAll(commandPhrases(language));
        return buildWithCommon(itemNames, defaults);
    }

    @NonNull
    public static ArrayList<String> multilingual(@NonNull List<String> itemNames) {
        Set<String> defaults = new LinkedHashSet<>();
        for (VoiceLanguage language : VoiceLanguage.values()) {
            defaults.addAll(common(language));
            defaults.addAll(commandPhrases(language));
        }
        return buildWithCommon(itemNames, new ArrayList<>(defaults));
    }

    private static ArrayList<String> buildWithCommon(List<String> itemNames, List<String> defaults) {
        ArrayList<String> hints = new ArrayList<>(MAX_HINTS);
        Set<String> seen = new LinkedHashSet<>();
        int customLimit = MAX_HINTS - defaults.size();
        for (String name : itemNames) {
            addCustomHint(name, customLimit, hints, seen);
            for (String part : NAME_SEPARATOR.split(name)) {
                addCustomHint(part, customLimit, hints, seen);
            }
            if (hints.size() >= customLimit) {
                break;
            }
        }
        for (String name : defaults) {
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                hints.add(name);
            }
        }
        return hints;
    }

    private static void addCustomHint(String value, int limit, List<String> hints, Set<String> seen) {
        String hint = StringsKt.trim(value).toString();
        if (hints.size() < limit && !hint.isEmpty() && hint.length() <= MAX_HINT_LENGTH
                && seen.add(hint.toLowerCase(Locale.ROOT))) {
            hints.add(hint);
        }
    }

    private static List<String> commandPhrases(VoiceLanguage language) {
        switch (language) {
            case ENGLISH:
                return Arrays.asList("add two eggs", "add 2 eggs", "used one egg", "used 1 egg",
                        "buy two bottles of milk", "buy 2 bottles of milk", "add one chicken wing", "deduct one egg");
            case CHINESE:
                return Arrays.asList("我买了两个鸡蛋", "添加两个鸡蛋", "用了一个鸡蛋",
                        "买两瓶牛奶", "买两瓶牛奶和一个鸡翅", "添加一根胡萝卜");
            case MALAY:
                return Arrays.asList("tambah dua biji telur", "beli dua botol susu", "guna satu biji telur",
                        "saya sudah guna satu telur", "tambah satu sayap ayam", "beli dua botol susu dan satu sayap ayam");
            default:
                throw new AssertionError(language);
        }
    }

    private static List<String> common(VoiceLanguage language) {
        switch (language) {
            case ENGLISH:
                return Arrays.asList("eggs", "milk", "Greek yogurt", "chicken wing", "bread", "rice",
                        "cooking oil", "soy sauce", "salt", "carrot", "tomato", "beef", "fish", "noodles",
                        "shampoo", "toilet paper", "dishwashing detergent", "coffee", "biscuits", "frozen dumplings");
            case CHINESE:
                return Arrays.asList("鸡蛋", "牛奶", "希腊酸奶", "鸡翅", "面包", "大米", "食用油",
                        "酱油", "盐", "胡萝卜", "番茄", "牛肉", "鱼", "面条", "添加", "用掉", "购买",
                        "洗发水", "卫生纸", "洗洁精", "咖啡", "饼干", "冷冻饺子");
            case MALAY:
                return Arrays.asList("telur", "susu", "yogurt", "sayap ayam", "roti", "beras", "minyak masak",
                        "kicap", "garam", "lobak merah", "tomato", "daging lembu", "ikan", "mi", "tambah", "guna", "beli",
                        "syampu", "tisu tandas", "pencuci pinggan", "kopi", "biskut", "ais krim");
            default:
                throw new AssertionError(language);
        }
    }
}

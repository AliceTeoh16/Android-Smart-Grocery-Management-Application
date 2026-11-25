package com.xmum.smartgrocery.utils;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets Malay whole-number item counts. */
final class MalayVoiceSupport {
    static final String classifiers = "botol|kotak|bungkus|paket|pek|tin|biji|ekor|keping|batang|ulas|ikat|papan";
    private static final List<String> DIGITS =
            Arrays.asList("kosong", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "lapan", "sembilan");
    private static final String UNIT = digitAlternatives(1);
    private static final String TEEN = digitAlternatives(2);
    private static final Pattern COUNT =
            Pattern.compile("\\b(?:(?:" + UNIT + ") puluh(?: (?:" + UNIT + "))?|(?:" + TEEN
                    + ") belas|seratus|sebelas|sepuluh|sifar|" + digitAlternatives(0) + ")\\b");
    private static final Pattern ONE_WITH_CLASSIFIER = Pattern.compile("\\bse(" + classifiers + ")\\b");

    private MalayVoiceSupport() {
    }

    static String normalizeCounts(String text) {
        String expanded = ONE_WITH_CLASSIFIER.matcher(text).replaceAll("1 $1");
        Matcher matcher = COUNT.matcher(expanded);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Integer.toString(quantity(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String digitAlternatives(int start) {
        StringBuilder words = new StringBuilder(DIGITS.get(start));
        for (int i = start + 1; i < DIGITS.size(); i++) {
            words.append('|').append(DIGITS.get(i));
        }
        return words.toString();
    }

    private static int quantity(String value) {
        String[] words = value.split(" ");
        if (words.length > 1) {
            int firstDigit = DIGITS.indexOf(words[0]);
            if ("puluh".equals(words[1])) {
                return firstDigit * 10 + (words.length > 2 ? DIGITS.indexOf(words[2]) : 0);
            }
            return 10 + firstDigit;
        }
        switch (value) {
            case "sepuluh":
                return 10;
            case "sebelas":
                return 11;
            case "seratus":
                return 100;
            case "sifar":
                return 0;
            default:
                return DIGITS.indexOf(value);
        }
    }
}

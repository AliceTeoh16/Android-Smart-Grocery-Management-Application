package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public enum VoiceLanguage {
    ENGLISH("en-US", "English"),
    MALAY("ms-MY", "Bahasa Melayu"),
    CHINESE("zh-CN", "中文 (Mandarin)");

    public static final List<String> supportedTags = Collections.unmodifiableList(
            Arrays.asList(ENGLISH.tag, MALAY.tag, CHINESE.tag));

    private final String tag;
    private final String label;

    VoiceLanguage(String tag, String label) {
        this.tag = tag;
        this.label = label;
    }

    @NonNull
    public String getTag() {
        return tag;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    @Nullable
    public static VoiceLanguage detected(@Nullable String tag) {
        if (tag == null) {
            return null;
        }
        int separator = tag.indexOf('-');
        String prefix = separator < 0 ? tag : tag.substring(0, separator);
        switch (prefix.toLowerCase(Locale.ROOT)) {
            case "en":
                return ENGLISH;
            case "ms":
                return MALAY;
            case "zh":
            case "cmn":
                return CHINESE;
            default:
                return null;
        }
    }

    @NonNull
    public static VoiceLanguage fromTag(@Nullable String tag) {
        VoiceLanguage language = detected(tag);
        return language != null ? language : ENGLISH;
    }
}

package com.xmum.smartgrocery.ui.recipes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation only. Does not change requests, retries, model selection or generation. */
public final class ChecklistErrorMessage {
    private static final Pattern HTTP_CODE = Pattern.compile("(?i)(?:http\\s*|[\"']?code[\"']?\\s*[:=]\\s*[\"']?)([45]\\d\\d)(?!\\d)");
    private final String title;
    private final String message;
    private final boolean showRetry;

    private ChecklistErrorMessage(String title, String message, boolean showRetry) {
        this.title = title; this.message = message; this.showRetry = showRetry;
    }

    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public boolean getShowRetry() { return showRetry; }

    public static ChecklistErrorMessage from(String raw) {
        String original = raw == null ? "" : raw.trim();
        if (original.startsWith("Enter a dish request first"))
            return new ChecklistErrorMessage("Enter a dish request", "Enter the dish you would like to cook, then try again.", false);
        if (original.startsWith("Couldn’t load checklist history"))
            return new ChecklistErrorMessage("Checklist history unavailable", "Couldn’t load your saved checklists. Reopen this screen to try again.", false);

        Details details = new Details();
        inspect(original, details, 0);
        String text = details.text.toString().toLowerCase(Locale.ROOT);
        if (details.codes.contains(401) || details.codes.contains(402) || details.codes.contains(403)
                || text.contains("api key") || text.contains("billing") || text.contains("not configured"))
            return new ChecklistErrorMessage("AI service unavailable", "The AI service isn’t available right now. Please contact app support if this continues.", false);
        if (details.codes.contains(429) || text.contains("resource_exhausted") || text.contains("quota"))
            return new ChecklistErrorMessage("AI service limit reached", "The AI service has reached its usage limit. Please try again later. Your request has been kept.", true);
        if (details.codes.contains(503) || text.contains("high demand") || text.contains("overloaded") || text.contains("unavailable"))
            return new ChecklistErrorMessage("AI service is busy", "The AI service is busy or temporarily unavailable. Please try again later. Your request has been kept.", true);
        if (details.codes.contains(504) || text.contains("timed out") || text.contains("timeout"))
            return new ChecklistErrorMessage("The request took too long", "The AI service didn’t respond in time. Please try again. Your request has been kept.", true);
        if (text.contains("failed to connect") || text.contains("unable to resolve host") || text.contains("connection reset")
                || text.contains("couldn’t reach") || text.contains("failed to fetch"))
            return new ChecklistErrorMessage("Connection problem", "Couldn’t reach the AI service. Check your internet connection and try again.", true);
        return new ChecklistErrorMessage("Couldn’t create checklist", "We couldn’t create your checklist this time. Please try again later. Your request has been kept.", true);
    }

    private static final class Details {
        final StringBuilder text = new StringBuilder();
        final Set<Integer> codes = new HashSet<>();
    }

    private static void inspect(String raw, Details details, int depth) {
        if (raw == null || depth > 5) return;
        String value = raw.length() > 20000 ? raw.substring(0, 20000) : raw;
        details.text.append(' ').append(value);
        Matcher matcher = HTTP_CODE.matcher(value.replace("\\\"", "\""));
        while (matcher.find()) details.codes.add(Integer.parseInt(matcher.group(1)));
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("code") && object.get("code").isJsonPrimitive()) {
                    try { details.codes.add(object.get("code").getAsInt()); } catch (RuntimeException ignored) { }
                }
                for (String field : new String[]{"error", "detail", "message", "status"}) {
                    JsonElement nested = object.get(field);
                    if (nested == null || nested.isJsonNull()) continue;
                    inspect(nested.isJsonPrimitive() ? nested.getAsString() : nested.toString(), details, depth + 1);
                }
            } else if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
                String decoded = parsed.getAsString();
                if (!decoded.equals(value)) inspect(decoded, details, depth + 1);
            }
        } catch (RuntimeException ignored) {
            // Unknown/HTML/malformed errors use fixed safe text, never the raw response.
        }
    }
}

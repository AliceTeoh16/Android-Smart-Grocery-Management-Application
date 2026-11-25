package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import kotlin.text.StringsKt;

/** Read-only interpretation of a transcript before it is applied to a list. */
public final class VoiceCommandReview {
    private final String transcript;
    private final List<VoiceCommand> commands;
    private final String error;

    public VoiceCommandReview(@NonNull String transcript, @NonNull List<VoiceCommand> commands,
            @Nullable String error) {
        this.transcript = transcript;
        this.commands = commands;
        this.error = error;
    }

    @NonNull
    public String getTranscript() {
        return transcript;
    }

    @NonNull
    public List<VoiceCommand> getCommands() {
        return commands;
    }

    @Nullable
    public String getError() {
        return error;
    }

    public boolean getCanConfirm() {
        return error == null;
    }

    @NonNull
    public static VoiceCommandReview prepare(@NonNull List<String> alternatives, boolean shopping) {
        VoiceCommandReview first = null;
        VoiceCommandReview firstValid = null;
        for (String text : alternatives) {
            if (StringsKt.isBlank(text)) {
                continue;
            }
            List<VoiceCommand> commands = VoiceParser.parseAll(text);
            String error = errorFor(commands, shopping);
            VoiceCommandReview result = new VoiceCommandReview(StringsKt.trim(text).toString(), commands, error);
            if (first == null) {
                first = result;
            }
            if (firstValid == null && result.getCanConfirm()) {
                firstValid = result;
            }
        }
        if (firstValid != null) {
            return firstValid;
        }
        return first != null ? first : new VoiceCommandReview("", Collections.emptyList(),
                "No command was detected. Please try again.");
    }

    @Nullable
    private static String errorFor(List<VoiceCommand> commands, boolean shopping) {
        if (commands.isEmpty() || commands.stream().anyMatch(command ->
                command.getAction() == VoiceAction.UNKNOWN || StringsKt.isBlank(command.getItemName()))) {
            return "Couldn’t understand the whole command. Please edit it or try again.";
        }
        if (commands.stream().anyMatch(command -> command.getAction() != VoiceAction.RECIPE && command.getQuantity() <= 0)) {
            return "Use a whole-number quantity greater than zero.";
        }
        if (shopping && commands.stream().anyMatch(command -> command.getAction() == VoiceAction.DEDUCT)) {
            return "Shopping voice commands can add items. Use Inventory to deduct groceries.";
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VoiceCommandReview)) {
            return false;
        }
        VoiceCommandReview review = (VoiceCommandReview) other;
        return transcript.equals(review.transcript) && commands.equals(review.commands)
                && Objects.equals(error, review.error);
    }

    @Override
    public int hashCode() {
        return (transcript.hashCode() * 31 + commands.hashCode()) * 31 + Objects.hashCode(error);
    }
}

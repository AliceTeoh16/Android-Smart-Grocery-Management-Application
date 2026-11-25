package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;

public final class VoiceCommand {
    private final VoiceAction action;
    private final int quantity;
    private final String itemName;

    public VoiceCommand(@NonNull VoiceAction action, int quantity, @NonNull String itemName) {
        this.action = action;
        this.quantity = quantity;
        this.itemName = itemName;
    }

    @NonNull
    public VoiceAction getAction() {
        return action;
    }

    public int getQuantity() {
        return quantity;
    }

    @NonNull
    public String getItemName() {
        return itemName;
    }

    @NonNull
    public VoiceCommand withItemName(@NonNull String name) {
        return new VoiceCommand(action, quantity, name);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VoiceCommand)) {
            return false;
        }
        VoiceCommand command = (VoiceCommand) other;
        return action == command.action && quantity == command.quantity && itemName.equals(command.itemName);
    }

    @Override
    public int hashCode() {
        return (action.hashCode() * 31 + quantity) * 31 + itemName.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "VoiceCommand(action=" + action + ", quantity=" + quantity + ", itemName=" + itemName + ")";
    }
}

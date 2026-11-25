package com.xmum.smartgrocery.ui.main;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelKt;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.repository.GroceryRepository;
import com.xmum.smartgrocery.data.repository.ShoppingListRepository;
import com.xmum.smartgrocery.data.session.SessionManager;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.ShoppingVoiceMerge;
import com.xmum.smartgrocery.utils.VoiceAction;
import com.xmum.smartgrocery.utils.VoiceCommand;
import com.xmum.smartgrocery.utils.VoiceCommandReview;
import com.xmum.smartgrocery.utils.VoiceParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;

public final class MainViewModel extends AndroidViewModel {
    private static final String[] SHOPPING_SUFFIXES = {
            "to shopping list", "to my shopping list", "in shopping list", "in my shopping list", "shopping list"
    };

    private final GroceryRepository groceryRepository;
    private final ShoppingListRepository shoppingRepository;
    private final SessionManager sessions;
    private final CoroutineScope scope;
    private final MutableSharedFlow<VoiceResult> events = SharedFlowKt.MutableSharedFlow(0, 0, BufferOverflow.SUSPEND);
    private final SharedFlow<VoiceResult> voiceResultEvent = FlowKt.asSharedFlow(events);

    public MainViewModel(@NonNull Application application) {
        super(application);
        SmartGroceryApp app = (SmartGroceryApp) application;
        groceryRepository = app.getGroceryRepository();
        shoppingRepository = app.getShoppingListRepository();
        sessions = app.getSessionManager();
        scope = ViewModelKt.getViewModelScope(this);
    }

    @NonNull
    public SharedFlow<VoiceResult> getVoiceResultEvent() {
        return voiceResultEvent;
    }

    public void processVoiceCommand(@NonNull String text) {
        processVoiceCommandOptions(Collections.singletonList(text));
    }

    public void processVoiceCommandOptions(@NonNull List<String> commandTexts) {
        AndroidCoroutines.<Unit>launch(scope, next -> applyInventoryCommands(parseOptions(commandTexts), null, next));
    }

    public void processConfirmedInventoryVoiceCommand(@NonNull String transcript,
            @NonNull Map<Integer, GroceryItem> newItems) {
        processConfirmedInventoryVoiceCommand(transcript, newItems, null);
    }

    /** Applies the complete review after the user confirms its items and destination. */
    public void processConfirmedInventoryVoiceCommand(@NonNull String transcript,
            @NonNull Map<Integer, GroceryItem> newItems, @Nullable DataScope target) {
        VoiceCommandReview review = VoiceCommandReview.prepare(Collections.singletonList(transcript), false);
        if (!review.getCanConfirm()) {
            return;
        }
        AndroidCoroutines.<Unit>launch(scope, next -> AndroidCoroutines.<Unit>recoverWith(
                apply -> target == null ? applyInventoryCommands(review.getCommands(), newItems, apply)
                        : sessions.runInInventoryDestination(target,
                                inScope -> applyInventoryCommands(review.getCommands(), newItems, inScope), apply),
                (error, resumed) -> emit(new VoiceResult.Error(error.getMessage() == null
                        ? "Could not save inventory." : error.getMessage()), resumed), next));
    }

    public void processShoppingVoiceCommandOptions(@NonNull List<String> commandTexts) {
        processShoppingVoiceCommandOptions(commandTexts, "Household");
    }

    public void processShoppingVoiceCommandOptions(@NonNull List<String> commandTexts, @NonNull String assignedTo) {
        AndroidCoroutines.<Unit>launch(scope, next -> {
            VoiceCommandReview review = VoiceCommandReview.prepare(commandTexts, true);
            if (!review.getCanConfirm() || !ShoppingVoiceMerge.assignees.contains(assignedTo)) {
                String error = review.getError();
                return emit(new VoiceResult.Error(error != null ? error : "Choose Me only or Household."), next);
            }
            List<VoiceCommand> additions = new ArrayList<>();
            for (VoiceCommand command : review.getCommands()) {
                if (command.getAction() == VoiceAction.ADD) {
                    additions.add(command.withItemName(cleanShoppingItemName(command.getItemName())));
                }
            }
            return AndroidCoroutines.<Unit>recoverWith(write -> AndroidCoroutines.<Unit, Unit>then(
                    apply -> additions.isEmpty() ? Unit.INSTANCE : shoppingRepository.addVoiceItems(additions, assignedTo, apply),
                    (unused, show) -> AndroidCoroutines.forEachAsync(review.getCommands(),
                            (command, event) -> emit(shoppingResult(command, assignedTo), event), show),
                    write),
                    (error, resumed) -> emit(new VoiceResult.Error(
                            "Couldn’t save this shopping command. No items were added. Please try again."), resumed), next);
        });
    }

    private Object applyInventoryCommands(List<VoiceCommand> commands, @Nullable Map<Integer, GroceryItem> newItems,
            Continuation<? super Unit> continuation) {
        return AndroidCoroutines.forEachIndexedAsync(commands, (index, command, next) -> {
            switch (command.getAction()) {
                case ADD:
                    GroceryItem reviewedItem = newItems != null ? newItems.get(index) : null;
                    if (reviewedItem != null) {
                        return AndroidCoroutines.<Long, Unit>then(insert -> groceryRepository.insert(reviewedItem, insert),
                                (unused, show) -> emit(new VoiceResult.Success(reviewedItem.getName() + " added to inventory"), show),
                                next);
                    }
                    return addExistingOrOpenForm(command, newItems != null, next);
                case DEDUCT:
                    return deduct(command, next);
                case RECIPE:
                    return emit(new VoiceResult.OpenRecipes(command.getItemName()), next);
                default:
                    return emit(new VoiceResult.Error("Command not recognized. Try: \"add milk\", \"used 2 eggs\", "
                            + "or \"suggest recipe with tomatoes\""), next);
            }
        }, continuation);
    }

    private Object addExistingOrOpenForm(VoiceCommand command, boolean confirmed, Continuation<? super Unit> completion) {
        return AndroidCoroutines.<GroceryItem, Unit>then(next -> findInventoryItem(command.getItemName(), next),
                (existing, next) -> {
                    if (existing == null) {
                        return emit(confirmed
                                ? new VoiceResult.Error("Inventory changed. Please review the voice command again.")
                                : new VoiceResult.OpenQuickAdd(command.getItemName(), command.getQuantity()), next);
                    }
                    int total = existing.getQuantity() + command.getQuantity();
                    return AndroidCoroutines.<Unit, Unit>then(write -> groceryRepository.updateQuantityFromVoiceAdd(
                            existing.getId(), total, write), (unused, show) ->
                            emit(new VoiceResult.Success("Increased quantity of " + existing.getName() + " by "
                                    + command.getQuantity() + " (Total: " + total + ")"), show), next);
                }, completion);
    }

    private Object deduct(VoiceCommand command, Continuation<? super Unit> completion) {
        return AndroidCoroutines.<GroceryItem, Unit>then(next -> findInventoryItem(command.getItemName(), next),
                (existing, next) -> {
                    if (existing == null) {
                        return emit(new VoiceResult.Error("Could not find \"" + command.getItemName()
                                + "\" in inventory to deduct."), next);
                    }
                    int total = Math.max(0, existing.getQuantity() - command.getQuantity());
                    String message = total == 0 ? "Used all " + existing.getName() + "! Quantity is now 0."
                            : "Deducted " + command.getQuantity() + " " + existing.getName() + ". Remaining: " + total;
                    return AndroidCoroutines.<Unit, Unit>then(
                            write -> groceryRepository.updateQuantityFromVoiceDeduct(existing.getId(), total, write),
                            (unused, show) -> emit(new VoiceResult.Success(message), show), next);
                }, completion);
    }

    private Object findInventoryItem(String name, Continuation<? super GroceryItem> completion) {
        return AndroidCoroutines.<GroceryItem, GroceryItem>then(next -> groceryRepository.getItemByName(name, next),
                (found, next) -> found != null ? found : groceryRepository.findBestMatchingItem(name, next), completion);
    }

    private static List<VoiceCommand> parseOptions(List<String> alternatives) {
        for (String alternative : alternatives) {
            List<VoiceCommand> commands = VoiceParser.parseAll(alternative);
            if (commands.stream().anyMatch(command -> command.getAction() != VoiceAction.UNKNOWN)) {
                return commands;
            }
        }
        return VoiceParser.parseAll(alternatives.isEmpty() ? null : alternatives.get(0));
    }

    private static VoiceResult shoppingResult(VoiceCommand command, String assignedTo) {
        switch (command.getAction()) {
            case ADD:
                return new VoiceResult.Success("Added " + command.getQuantity() + " "
                        + cleanShoppingItemName(command.getItemName()) + " for " + assignedTo + ".");
            case RECIPE:
                return new VoiceResult.OpenRecipes(command.getItemName());
            case DEDUCT:
                return new VoiceResult.Error("Shopping voice can add list items. Try: \"add bread to shopping list\"");
            default:
                return new VoiceResult.Error("Command not recognized. Try: \"add bread to shopping list\"");
        }
    }

    private static String cleanShoppingItemName(String name) {
        String cleaned = name;
        for (String suffix : SHOPPING_SUFFIXES) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
            }
        }
        cleaned = StringsKt.trim(cleaned).toString();
        return StringsKt.isBlank(cleaned) ? StringsKt.trim(name).toString() : cleaned;
    }

    private Object emit(VoiceResult result, Continuation<? super Unit> completion) {
        return events.emit(result, completion);
    }
}

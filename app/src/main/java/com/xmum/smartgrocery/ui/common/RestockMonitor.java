package com.xmum.smartgrocery.ui.common;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.RoomDatabaseKt;

import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity;
import com.xmum.smartgrocery.data.local.entity.LocalSessionEntity;
import com.xmum.smartgrocery.data.session.SessionManager;
import com.xmum.smartgrocery.data.sharing.SharingRuntime;
import com.xmum.smartgrocery.utils.AndroidCoroutines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/** Watches all accessible inventory without creating shopping items. */
public final class RestockMonitor {
    private static final Map<AppDatabase, RestockMonitor> INSTANCES = new WeakHashMap<>();

    private final AppDatabase database;
    private final SessionManager manager;
    private final NotificationHelper notifications;
    private final SharedPreferences preferences;
    private final CoroutineScope scope = AndroidCoroutines.ioScope();
    private final MutableStateFlow<Integer> wakeups = StateFlowKt.MutableStateFlow(0);

    public RestockMonitor(@NonNull Context context, @NonNull AppDatabase database) {
        this.database = database;
        manager = new SessionManager(database);
        notifications = new NotificationHelper(context);
        preferences = context.getSharedPreferences("restock_notifications", Context.MODE_PRIVATE);
    }

    @Nullable
    public static synchronized RestockMonitor install(@NonNull Context context, @NonNull AppDatabase database) {
        if (SharingRuntime.disabledForTests) {
            return null;
        }
        return INSTANCES.computeIfAbsent(database, key -> {
            RestockMonitor monitor = new RestockMonitor(context.getApplicationContext(), key);
            monitor.start();
            return monitor;
        });
    }

    public void refresh() {
        wakeups.setValue(wakeups.getValue() + 1);
    }

    @NonNull
    public Job start() {
        Flow<String> actors = FlowKt.map(manager.getSessions(),
                (LocalSessionEntity session, Continuation<? super String> completion) ->
                        session == null ? null : session.getUserId());
        return AndroidCoroutines.<Unit>launch(scope, completion ->
                AndroidCoroutines.collectLatestValues(FlowKt.distinctUntilChanged(actors), this::watchAccount, completion));
    }

    public void stop() {
        CoroutineScopeKt.cancel(scope, null);
        notifications.cancelRestockNotifications();
    }

    private Object watchAccount(String actor, Continuation<? super Unit> completion) {
        notifications.cancelRestockNotifications();
        if (actor == null) {
            return Unit.INSTANCE;
        }
        Flow<StockChange> updates = FlowKt.combine(database.groceryDao().observeAccessibleItems(),
                database.shoppingListDao().observeUnapprovedRestockCount(), wakeups,
                (items, automatic, wakeup, next) -> new StockChange(items, automatic));
        return AndroidCoroutines.<Unit, Unit>then(next -> removeUnapproved(actor, next),
                (ignored, next) -> AndroidCoroutines.collectValues(updates,
                        (change, save) -> onChange(actor, change, save), next), completion);
    }

    private Object removeUnapproved(String actor, Continuation<? super Unit> completion) {
        return RoomDatabaseKt.withTransaction(database, next -> AndroidCoroutines.<String, Unit>then(
                manager::currentUserId, (current, checked) -> {
                    if (!actor.equals(current)) {
                        return Unit.INSTANCE;
                    }
                    return AndroidCoroutines.<Integer, Unit>then(database.shoppingListDao()::removeUnapprovedRestocks,
                            (removed, done) -> Unit.INSTANCE, checked);
                }, next), completion);
    }

    private Object onChange(String actor, StockChange change, Continuation<? super Unit> completion) {
        return AndroidCoroutines.<Unit, Unit>then(
                next -> change.automatic > 0 ? removeUnapproved(actor, next) : Unit.INSTANCE,
                (ignored, next) -> AndroidCoroutines.<String, Unit>then(manager::currentUserId,
                        (current, checked) -> actor.equals(current)
                                ? postNotifications(actor, change.items, checked) : Unit.INSTANCE, next),
                completion);
    }

    private Object postNotifications(String actor, List<GroceryItem> items, Continuation<? super Unit> completion) {
        return AndroidCoroutines.<List<HouseholdEntity>, Unit>then(manager::households, (homes, next) -> {
            Map<String, String> homeNames = new HashMap<>();
            for (HouseholdEntity home : homes) {
                homeNames.put(home.getHouseholdId(), home.getName());
            }
            List<GroceryItem> low = new ArrayList<>();
            Set<String> tags = new HashSet<>();
            Set<String> keys = new HashSet<>();
            for (GroceryItem item : items) {
                if (item.getQuantity() <= item.getThreshold()) {
                    low.add(item);
                    DataScope target = new DataScope(actor, item.getHouseholdId());
                    tags.add(NotificationHelper.restockTag(item, target));
                    keys.add(itemKey(actor, item));
                }
            }
            notifications.retainRestockNotifications(tags);
            Set<String> notified = new HashSet<>(preferences.getStringSet(actor, Collections.emptySet()));
            notified.retainAll(keys);
            return AndroidCoroutines.<Unit, Unit>then(walk -> AndroidCoroutines.forEachAsync(low, (item, check) -> {
                String key = itemKey(actor, item);
                if (notified.contains(key)) {
                    return Unit.INSTANCE;
                }
                return AndroidCoroutines.<String, Unit>then(manager::currentUserId, (current, done) -> {
                    if (actor.equals(current)) {
                        String name = homeNames.get(item.getHouseholdId());
                        DataScope target = new DataScope(actor, item.getHouseholdId());
                        if (notifications.sendRestockNotification(item, target, name == null ? "Personal (private)" : name)) {
                            notified.add(key);
                        }
                    }
                    return Unit.INSTANCE;
                }, check);
            }, walk), (ignored, save) -> {
                preferences.edit().putStringSet(actor, notified).apply();
                return Unit.INSTANCE;
            }, next);
        }, completion);
    }

    private static String itemKey(String actor, GroceryItem item) {
        return actor + ":" + (item.getHouseholdId() == null ? "personal" : item.getHouseholdId()) + ":" + item.getId();
    }

    private static final class StockChange {
        private final List<GroceryItem> items;
        private final int automatic;

        private StockChange(List<GroceryItem> items, int automatic) {
            this.items = items;
            this.automatic = automatic;
        }
    }
}

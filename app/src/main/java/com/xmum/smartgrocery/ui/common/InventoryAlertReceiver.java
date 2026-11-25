package com.xmum.smartgrocery.ui.common;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.SmartGroceryApp;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.DateUtils;

import java.util.List;

import kotlin.Unit;

public final class InventoryAlertReceiver extends BroadcastReceiver {
    private static final int REQUEST_CODE = 401;
    private static final long DAILY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L;

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        schedule(context);
        PendingResult pending = goAsync();
        SmartGroceryApp app = (SmartGroceryApp) context.getApplicationContext();
        AndroidCoroutines.<Unit>launch(AndroidCoroutines.ioScope(), completion ->
                AndroidCoroutines.withFinally(next -> {
                    int warningDays = app.getPreferencesRepository().getExpiryWarningDays().getValue();
                    NotificationHelper notifications = new NotificationHelper(app);
                    return AndroidCoroutines.<List<GroceryItem>, Unit>then(
                            app.getGroceryRepository()::getAllItemsOnce,
                            (items, finished) -> {
                                for (GroceryItem item : items) {
                                    Long expiry = item.getExpiryDate();
                                    if (item.getQuantity() > 0 && !DateUtils.isExpired(expiry)
                                            && DateUtils.isExpiringWithin(expiry, warningDays)) {
                                        notifications.sendExpiringSoonNotification(item.getName(), DateUtils.daysUntil(expiry));
                                    }
                                }
                                return Unit.INSTANCE;
                            }, next);
                }, pending::finish, completion));
    }

    public static void schedule(@NonNull Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent alarm = new Intent(context, InventoryAlertReceiver.class);
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST_CODE, alarm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000L,
                DAILY_INTERVAL_MILLIS, pending);
    }
}

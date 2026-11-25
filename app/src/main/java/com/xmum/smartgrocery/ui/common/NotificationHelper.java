package com.xmum.smartgrocery.ui.common;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.ui.inventory.RestockReviewActivity;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public final class NotificationHelper {
    private static final String CHANNEL_ID = "smart_grocery_alerts";
    private static final int LOW_STOCK_ID = 101;
    private static final int EXPIRY_ID = 102;
    private static final int BUDGET_ID = 103;

    private final Context context;
    private final NotificationManager manager;

    public NotificationHelper(@NonNull Context context) {
        this.context = context;
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Smart Grocery Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notifications for inventory alerts and budget exceed limits");
            manager.createNotificationChannel(channel);
        }
    }

    static String restockTag(GroceryItem item, DataScope target) {
        return "restock:" + target.getUserId() + ":" + target.getKey() + ":" + item.getId();
    }

    public boolean sendRestockNotification(@NonNull GroceryItem item, @NonNull DataScope target, @NonNull String scopeName) {
        if (!canPostNotifications() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false;
        }
        Intent intent = RestockReviewActivity.Companion.intent(context, target);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(restockTag(item, target), LOW_STOCK_ID, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Low stock: " + item.getName())
                .setContentText(scopeName + " · " + item.getQuantity() + " left. Review before adding to Shopping List.")
                .setContentIntent(pending)
                .addAction(0, "Review restock", pending)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build());
        return true;
    }

    public void retainRestockNotifications(@NonNull Set<String> tags) {
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            String tag = notification.getTag();
            if (tag != null && tag.startsWith("restock:") && !tags.contains(tag)) {
                manager.cancel(tag, notification.getId());
            }
        }
    }

    public void cancelRestockNotifications() {
        retainRestockNotifications(Collections.emptySet());
    }

    public void sendExpiringSoonNotification(@NonNull String itemName, int daysLeft) {
        if (!canPostNotifications()) {
            return;
        }
        manager.notify(EXPIRY_ID + itemName.hashCode(), new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Expiring Soon Alert")
                .setContentText(itemName + " is expiring soon! Days remaining: " + daysLeft + ".")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build());
    }

    public void sendOverBudgetNotification(double spent, double limit) {
        if (!canPostNotifications()) {
            return;
        }
        manager.notify(BUDGET_ID, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Budget Alert!")
                .setContentText(String.format(Locale.getDefault(),
                        "You have exceeded your monthly budget. Spent: RM %.2f / Limit: RM %.2f", spent, limit))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build());
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}

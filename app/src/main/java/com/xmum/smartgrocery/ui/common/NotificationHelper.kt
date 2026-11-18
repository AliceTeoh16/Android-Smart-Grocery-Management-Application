package com.xmum.smartgrocery.ui.common

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xmum.smartgrocery.R

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "smart_grocery_alerts"
        private const val CHANNEL_NAME = "Smart Grocery Alerts"
        private const val CHANNEL_DESC = "Notifications for inventory alerts and budget exceed limits"
        private const val LOW_STOCK_NOTIFICATION_ID = 101
        private const val EXPIRY_NOTIFICATION_ID = 102
        private const val BUDGET_NOTIFICATION_ID = 103
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun sendRestockNotification(item: com.xmum.smartgrocery.data.local.entity.GroceryItem,
        target: com.xmum.smartgrocery.data.local.dao.DataScope, scopeName: String): Boolean {
        if (!canPostNotifications() || !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val intent = com.xmum.smartgrocery.ui.inventory.RestockReviewActivity.intent(context, target)
        val pending = android.app.PendingIntent.getActivity(context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Low stock: " + item.name)
            .setContentText(scopeName + " · " + item.quantity + " left. Review before adding to Shopping List.")
            .setContentIntent(pending)
            .addAction(0, "Review restock", pending)
            .setOnlyAlertOnce(true).setAutoCancel(true).build()
        notificationManager.notify("restock:" + target.userId + ":" + target.key + ":" + item.id,
            LOW_STOCK_NOTIFICATION_ID, notification)
        return true
    }

    fun retainRestockNotifications(tags: Set<String>) {
        notificationManager.activeNotifications.filter { it.tag?.startsWith("restock:") == true && it.tag !in tags }
            .forEach { notificationManager.cancel(it.tag, it.id) }
    }

    fun cancelRestockNotifications() {
        notificationManager.activeNotifications.filter { it.tag?.startsWith("restock:") == true }
            .forEach { notificationManager.cancel(it.tag, it.id) }
    }

    fun sendExpiringSoonNotification(itemName: String, daysLeft: Int) {
        if (!canPostNotifications()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Expiring Soon Alert")
            .setContentText("$itemName is expiring soon! Days remaining: $daysLeft.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(EXPIRY_NOTIFICATION_ID + itemName.hashCode(), builder.build())
    }

    fun sendOverBudgetNotification(spent: Double, limit: Double) {
        if (!canPostNotifications()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Budget Alert!")
            .setContentText("You have exceeded your monthly budget. Spent: RM ${String.format("%.2f", spent)} / Limit: RM ${String.format("%.2f", limit)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(BUDGET_NOTIFICATION_ID, builder.build())
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}

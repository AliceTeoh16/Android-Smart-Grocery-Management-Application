package com.xmum.smartgrocery.ui.common

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InventoryAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        schedule(context)

        val pendingResult = goAsync()
        val app = context.applicationContext as SmartGroceryApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val warningDays = app.preferencesRepository.expiryWarningDays.value
                val notificationHelper = NotificationHelper(context.applicationContext)
                app.groceryRepository.getAllItemsOnce()
                    .filter { it.quantity > 0 }
                    .forEach { item ->
                        if (!DateUtils.isExpired(item.expiryDate) &&
                            DateUtils.isExpiringWithin(item.expiryDate, warningDays)
                        ) {
                            notificationHelper.sendExpiringSoonNotification(
                                item.name,
                                DateUtils.daysUntil(item.expiryDate)
                            )
                        }
                    }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 401
        private const val DAILY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, InventoryAlertReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val firstRun = SystemClock.elapsedRealtime() + 60_000L
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                firstRun,
                DAILY_INTERVAL_MILLIS,
                pendingIntent
            )
        }
    }
}

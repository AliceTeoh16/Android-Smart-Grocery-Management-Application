package com.xmum.smartgrocery.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {

    private const val DATE_FORMAT = "yyyy-MM-dd"
    private const val MONTH_YEAR_FORMAT = "yyyy-MM"

    fun formatDate(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "Not Set"
        val sdf = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun parseDate(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
            sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    fun getCurrentMonthYear(): String {
        val sdf = SimpleDateFormat(MONTH_YEAR_FORMAT, Locale.getDefault())
        return sdf.format(Date())
    }

    fun getStartOfMonth(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun getEndOfMonth(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }

    fun getStartOfWeek(): Long {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun getStartOfLast30Days(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -29)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun getEndOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }

    fun getStartOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun isExpired(timestamp: Long?): Boolean {
        return timestamp != null && timestamp > 0L && timestamp < getStartOfToday()
    }

    fun isExpiringWithin(timestamp: Long?, warningDays: Int): Boolean {
        val warningWindowMillis = warningDays.coerceAtLeast(1) * 24 * 60 * 60 * 1000L
        return timestamp != null && timestamp > 0L && timestamp in getStartOfToday()..(getEndOfToday() + warningWindowMillis)
    }

    fun daysUntil(timestamp: Long?): Int {
        require(timestamp != null && timestamp > 0L) { "An expiry date is required for a countdown." }
        val millisLeft = (timestamp - System.currentTimeMillis()).coerceAtLeast(0L)
        return java.util.concurrent.TimeUnit.MILLISECONDS.toDays(millisLeft).toInt()
    }
}

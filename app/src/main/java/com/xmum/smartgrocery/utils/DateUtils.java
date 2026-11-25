package com.xmum.smartgrocery.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class DateUtils {
    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final String MONTH_YEAR_FORMAT = "yyyy-MM";

    private DateUtils() {
    }

    @NonNull
    public static String formatDate(@Nullable Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return "Not Set";
        }
        return new SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(new Date(timestamp));
    }

    public static long parseDate(@NonNull String dateStr) {
        try {
            Date date = new SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).parse(dateStr);
            return date != null ? date.getTime() : System.currentTimeMillis();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    @NonNull
    public static String getCurrentMonthYear() {
        return new SimpleDateFormat(MONTH_YEAR_FORMAT, Locale.getDefault()).format(new Date());
    }

    public static long getStartOfMonth() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        return startOfDay(calendar);
    }

    public static long getEndOfMonth() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        return endOfDay(calendar);
    }

    public static long getStartOfWeek() {
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        return startOfDay(calendar);
    }

    public static long getStartOfLast30Days() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -29);
        return startOfDay(calendar);
    }

    public static long getEndOfToday() {
        return endOfDay(Calendar.getInstance());
    }

    public static long getStartOfToday() {
        return startOfDay(Calendar.getInstance());
    }

    public static boolean isExpired(@Nullable Long timestamp) {
        return timestamp != null && timestamp > 0 && timestamp < getStartOfToday();
    }

    public static boolean isExpiringWithin(@Nullable Long timestamp, int warningDays) {
        long warningWindowMillis = Math.max(warningDays, 1) * 24 * 60 * 60 * 1000L;
        return timestamp != null && timestamp > 0 && timestamp >= getStartOfToday()
                && timestamp <= getEndOfToday() + warningWindowMillis;
    }

    public static int daysUntil(@Nullable Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            throw new IllegalArgumentException("An expiry date is required for a countdown.");
        }
        long millisLeft = Math.max(timestamp - System.currentTimeMillis(), 0);
        return (int) TimeUnit.MILLISECONDS.toDays(millisLeft);
    }

    private static long startOfDay(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static long endOfDay(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis();
    }
}

package com.xmum.smartgrocery.ui.history;

import androidx.annotation.NonNull;

import com.xmum.smartgrocery.data.local.entity.InventoryChangeType;
import com.xmum.smartgrocery.data.local.entity.InventoryLog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

import kotlin.text.StringsKt;

final class PurchaseHistoryMapper {
    private PurchaseHistoryMapper() {
    }

    @NonNull
    static List<PurchaseHistoryRecord> records(@NonNull List<InventoryLog> logs, @NonNull String search,
            boolean thisMonth, long now) {
        Calendar month = Calendar.getInstance();
        month.setTimeInMillis(now);
        month.set(Calendar.DAY_OF_MONTH, 1);
        month.set(Calendar.HOUR_OF_DAY, 0);
        month.set(Calendar.MINUTE, 0);
        month.set(Calendar.SECOND, 0);
        month.set(Calendar.MILLISECOND, 0);
        long start = month.getTimeInMillis();
        month.add(Calendar.MONTH, 1);
        long end = month.getTimeInMillis();

        String term = StringsKt.trim(search).toString();
        List<InventoryLog> filtered = new ArrayList<>();
        for (InventoryLog log : logs) {
            if (!InventoryChangeType.ADD.equals(log.getChangeType()) || log.getAmountChanged() <= 0) {
                continue;
            }
            if (thisMonth && (log.getTimestamp() < start || log.getTimestamp() >= end)) {
                continue;
            }
            if (term.isEmpty() || StringsKt.contains(log.getItemName(), term, true)
                    || StringsKt.contains(log.getCategory(), term, true)) {
                filtered.add(log);
            }
        }
        filtered.sort(Comparator.comparingLong(InventoryLog::getTimestamp)
                .thenComparingInt(InventoryLog::getId).reversed());
        List<PurchaseHistoryRecord> records = new ArrayList<>(filtered.size());
        for (InventoryLog log : filtered) {
            records.add(new PurchaseHistoryRecord(log.getId(), log.getItemName(), log.getCategory(),
                    log.getAmountChanged(), log.getUnitPriceAtChange(), log.getTimestamp(), log.getUnit()));
        }
        return records;
    }
}

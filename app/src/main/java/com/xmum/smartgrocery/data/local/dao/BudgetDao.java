package com.xmum.smartgrocery.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Transaction;

import com.xmum.smartgrocery.data.local.entity.BudgetEntry;

import java.util.List;
import java.util.Objects;

import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import kotlinx.coroutines.flow.Flow;

@Dao
public abstract class BudgetDao extends ScopedDao {
    private static final String MONTH_QUERY = "SELECT * FROM budget_entries WHERE monthYear = :monthYear AND "
            + ScopeSql.MATCH + " LIMIT 1";

    protected BudgetDao(@NonNull RoomDatabase database) {
        super(database);
    }

    @NonNull
    @Query(MONTH_QUERY)
    public abstract Flow<BudgetEntry> getBudgetForMonth(@NonNull String monthYear);

    @Nullable
    @Query(MONTH_QUERY)
    protected abstract BudgetEntry readBudget(@NonNull String monthYear);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract long setBudgetRaw(@NonNull BudgetEntry budget);

    @Transaction
    protected long writeBudget(@NonNull BudgetEntry budget, @Nullable DataScope expectedScope) {
        DataScope scope = requireScopeNow();
        if (expectedScope != null && !expectedScope.equals(scope)) {
            throw new IllegalArgumentException("Budget scope changed. Please set the limit again.");
        }
        if (!Double.isFinite(budget.getLimitAmount()) || budget.getLimitAmount() < 0) {
            throw new IllegalArgumentException("Enter a valid budget amount.");
        }
        boolean anonymousDraft = StringsKt.isBlank(budget.getUserId());
        if ((!StringsKt.isBlank(budget.getScopeKey()) && !budget.getScopeKey().equals(scope.getKey()))
                || (!anonymousDraft && !Objects.equals(budget.getHouseholdId(), scope.getHouseholdId()))) {
            throw new IllegalArgumentException("Budget scope changed; reload first.");
        }

        BudgetEntry existing = readBudget(budget.getMonthYear());
        if (existing == null) {
            scope.requireDraft(budget.getUserId(), budget.getHouseholdId());
        } else if (!anonymousDraft && !budget.getUserId().equals(existing.getUserId())) {
            throw new IllegalArgumentException("Invalid budget owner.");
        }

        String owner = existing == null ? scope.getUserId() : existing.getUserId();
        return setBudgetRaw(new BudgetEntry(budget.getMonthYear(), budget.getLimitAmount(), owner,
                scope.getHouseholdId(), scope.getKey()));
    }

    @Nullable
    public final Object readBudgetAsync(@NonNull String monthYear,
            @NonNull Continuation<? super BudgetEntry> continuation) {
        return execute(false, () -> readBudget(monthYear), continuation);
    }

    @NonNull
    public final Object setBudgetAsync(@NonNull BudgetEntry budget, @Nullable DataScope expectedScope,
            @NonNull Continuation<? super Long> continuation) {
        return execute(true, () -> writeBudget(budget, expectedScope), continuation);
    }

    @NonNull
    @Query("SELECT SUM(amountChanged * unitPriceAtChange) FROM inventory_logs WHERE changeType = 'ADD' "
            + "AND timestamp >= :startOfMonth AND timestamp <= :endOfMonth AND " + ScopeSql.MATCH)
    public abstract Flow<Double> getTotalSpentForMonth(long startOfMonth, long endOfMonth);

    @NonNull
    @Query("SELECT category, SUM(amountChanged * unitPriceAtChange) AS categoryTotal FROM inventory_logs "
            + "WHERE changeType = 'ADD' AND timestamp >= :startOfMonth AND timestamp <= :endOfMonth AND "
            + ScopeSql.MATCH + " GROUP BY category")
    public abstract Flow<List<CategorySpend>> getCategorySpending(long startOfMonth, long endOfMonth);
}

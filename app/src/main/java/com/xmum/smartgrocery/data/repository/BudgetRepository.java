package com.xmum.smartgrocery.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.dao.BudgetDao;
import com.xmum.smartgrocery.data.local.dao.CategorySpend;
import com.xmum.smartgrocery.data.local.dao.DataScope;
import com.xmum.smartgrocery.data.local.entity.BudgetEntry;

import java.util.List;

import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

public final class BudgetRepository {
    private final BudgetDao budgetDao;

    public BudgetRepository(@NonNull BudgetDao budgetDao) {
        this.budgetDao = budgetDao;
    }

    @NonNull
    public Flow<BudgetEntry> getBudgetForMonth(@NonNull String monthYear) {
        return budgetDao.getBudgetForMonth(monthYear);
    }

    @Nullable
    public Object getBudgetForMonthSync(@NonNull String monthYear,
            @NonNull Continuation<? super BudgetEntry> continuation) {
        return budgetDao.readBudgetAsync(monthYear, continuation);
    }

    @NonNull
    public Object setBudget(@NonNull BudgetEntry budget, @Nullable DataScope expectedScope,
            @NonNull Continuation<? super Long> continuation) {
        return budgetDao.setBudgetAsync(budget, expectedScope, continuation);
    }

    @NonNull
    public Flow<Double> getTotalSpentForMonth(long startOfMonth, long endOfMonth) {
        return budgetDao.getTotalSpentForMonth(startOfMonth, endOfMonth);
    }

    @NonNull
    public Flow<List<CategorySpend>> getCategorySpending(long startOfMonth, long endOfMonth) {
        return budgetDao.getCategorySpending(startOfMonth, endOfMonth);
    }
}

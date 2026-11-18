package com.xmum.smartgrocery.data.local.dao

import androidx.room.*
import com.xmum.smartgrocery.data.local.entity.BudgetEntry
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BudgetDao : ScopedDao() {
    @Query("SELECT * FROM budget_entries WHERE monthYear = :monthYear AND " + ScopeSql.MATCH + " LIMIT 1")
    abstract fun getBudgetForMonth(monthYear: String): Flow<BudgetEntry?>

    @Query("SELECT * FROM budget_entries WHERE monthYear = :monthYear AND " + ScopeSql.MATCH + " LIMIT 1")
    abstract suspend fun getBudgetForMonthSync(monthYear: String): BudgetEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun setBudgetRaw(budget: BudgetEntry): Long

    @Transaction
    open suspend fun setBudget(budget: BudgetEntry, expectedScope: DataScope? = null): Long {
        val scope = requireScope()
        require(expectedScope == null || expectedScope == scope) { "Budget scope changed. Please set the limit again." }
        require(budget.limitAmount.isFinite() && budget.limitAmount >= 0) { "Enter a valid budget amount." }
        require(budget.scopeKey.isBlank() || budget.scopeKey == scope.key) { "Budget scope changed; reload first." }
        require(budget.userId.isBlank() || budget.householdId == scope.householdId) { "Budget scope changed; reload first." }
        val existing = getBudgetForMonthSync(budget.monthYear)
        if (existing == null) scope.requireDraft(budget.userId, budget.householdId)
        else require(budget.userId.isBlank() || budget.userId == existing.userId) { "Invalid budget owner." }
        return setBudgetRaw(budget.copy(userId = existing?.userId ?: scope.userId,
            householdId = scope.householdId, scopeKey = scope.key))
    }

    @Query("SELECT SUM(amountChanged * unitPriceAtChange) FROM inventory_logs WHERE changeType = 'ADD' AND timestamp >= :startOfMonth AND timestamp <= :endOfMonth AND " + ScopeSql.MATCH)
    abstract fun getTotalSpentForMonth(startOfMonth: Long, endOfMonth: Long): Flow<Double?>

    @Query("SELECT category, SUM(amountChanged * unitPriceAtChange) AS categoryTotal FROM inventory_logs WHERE changeType = 'ADD' AND timestamp >= :startOfMonth AND timestamp <= :endOfMonth AND " + ScopeSql.MATCH + " GROUP BY category")
    abstract fun getCategorySpending(startOfMonth: Long, endOfMonth: Long): Flow<List<CategorySpend>>
}

data class CategorySpend(val category: String, val categoryTotal: Double)

package com.xmum.smartgrocery.data.repository

import com.xmum.smartgrocery.data.local.dao.BudgetDao
import com.xmum.smartgrocery.data.local.dao.CategorySpend
import com.xmum.smartgrocery.data.local.entity.BudgetEntry
import kotlinx.coroutines.flow.Flow

class BudgetRepository(private val budgetDao: BudgetDao) {

    fun getBudgetForMonth(monthYear: String): Flow<BudgetEntry?> {
        return budgetDao.getBudgetForMonth(monthYear)
    }

    suspend fun getBudgetForMonthSync(monthYear: String): BudgetEntry? {
        return budgetDao.getBudgetForMonthSync(monthYear)
    }

    suspend fun setBudget(budget: BudgetEntry, expectedScope: com.xmum.smartgrocery.data.local.dao.DataScope? = null) {
        budgetDao.setBudget(budget, expectedScope)
    }

    fun getTotalSpentForMonth(startOfMonth: Long, endOfMonth: Long): Flow<Double?> {
        return budgetDao.getTotalSpentForMonth(startOfMonth, endOfMonth)
    }

    fun getCategorySpending(startOfMonth: Long, endOfMonth: Long): Flow<List<CategorySpend>> {
        return budgetDao.getCategorySpending(startOfMonth, endOfMonth)
    }
}

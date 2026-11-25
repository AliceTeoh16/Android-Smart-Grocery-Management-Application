package com.xmum.smartgrocery.data.local.dao

import com.xmum.smartgrocery.data.local.entity.BudgetEntry
suspend fun BudgetDao.getBudgetForMonthSync(monthYear: String): BudgetEntry? =
    readBudgetAsync(monthYear)

suspend fun BudgetDao.setBudget(budget: BudgetEntry, expectedScope: DataScope? = null): Long =
    checkNotNull(setBudgetAsync(budget, expectedScope))

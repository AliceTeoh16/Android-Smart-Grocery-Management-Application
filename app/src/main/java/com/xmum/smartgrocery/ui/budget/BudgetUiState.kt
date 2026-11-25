package com.xmum.smartgrocery.ui.budget

data class BudgetUiState(
    val limit: Double = 0.0,
    val periodSpent: Double = 0.0,
    val monthlySpent: Double = 0.0,
    val remaining: Double = 0.0,
    val progressPercent: Int = 0,
    val spentLabel: String = "Spent this month"
)

enum class BudgetScope { PERSONAL, HOUSEHOLD }

enum class BudgetRange(val spentLabel: String) {
    WEEK("Spent this week"),
    MONTH("Spent this month"),
    LAST_30_DAYS("Spent last 30 days")
}

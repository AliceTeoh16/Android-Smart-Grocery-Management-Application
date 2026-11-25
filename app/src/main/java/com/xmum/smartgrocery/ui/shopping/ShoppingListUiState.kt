package com.xmum.smartgrocery.ui.shopping

enum class ShoppingMemberFilter(val label: String) {
    PERSONAL("Personal"),
    HOUSEHOLD("Household")
}

data class ShoppingBudgetImpact(
    val cartTotal: Double = 0.0,
    val totalBudget: Double = 0.0,
    val spentAmount: Double = 0.0,
    val remainingBudget: Double = 0.0
) {
    val exceedsBudget: Boolean
        get() = cartTotal > remainingBudget
}

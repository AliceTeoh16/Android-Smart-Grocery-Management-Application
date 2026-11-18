package com.xmum.smartgrocery.ui.budget

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.pantrypal.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.dao.CategorySpend
import com.xmum.smartgrocery.databinding.FragmentBudgetBinding
import com.xmum.smartgrocery.databinding.ItemCategorySpendBinding
import kotlinx.coroutines.launch

class BudgetFragment : Fragment() {

    private var _binding: FragmentBudgetBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BudgetViewModel by viewModels()
    private var displayedRange by mutableStateOf(BudgetRange.MONTH)
    private var displayedBudgetColor by mutableIntStateOf(0xFF315B40.toInt())
    private var displayedProgress by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBudgetBinding.inflate(inflater, container, false)
        return ComposeView(requireContext()).apply {
            id = R.id.budget_tracker_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        restoreLegacyUiState(binding.root, savedInstanceState)
        observeViewModel()
        (view as ComposeView).setContent {
            val dataContext by (requireActivity().application as com.xmum.smartgrocery.SmartGroceryApp).sessionManager.context.collectAsState(initial = null)
            val selectedScope by viewModel.activeScope.collectAsState(initial = BudgetScope.PERSONAL)
            LaunchedEffect(selectedScope) { binding.editBudgetLimit.text?.clear() }
            val limit by rememberBoundText(binding.textLimitValue)
            val spent by rememberBoundText(binding.textSpentValue)
            val spentLabel by rememberBoundText(binding.textSpentLabel)
            val remaining by rememberBoundText(binding.textRemainingValue)
            val remainingLabel by rememberBoundText(binding.textRemainingLabel)
            val percent by rememberBoundText(binding.textBudgetPercent)
            val input by rememberBoundText(binding.editBudgetLimit)
            var inputError by remember { mutableStateOf(binding.editBudgetLimit.error?.toString()) }
            val focusManager = LocalFocusManager.current
            val profile = remember { LocalAuthSession(requireContext()).currentProfile() }
            val indicatorColor = displayedBudgetColor
            val status = when (indicatorColor) {
                ContextCompat.getColor(requireContext(), R.color.alert_red) -> "Over budget"
                ContextCompat.getColor(requireContext(), R.color.premium_amber) -> "Near limit"
                else -> "On track"
            }
            PantryPalTheme {
                BudgetTrackerScreen(
                    selectedScope = selectedScope.ordinal,
                    onScopeSelected = { viewModel.setScope(BudgetScope.entries[it]) },
                    contextName = dataContext?.name ?: "Personal (private)",
                    onContext = { com.xmum.smartgrocery.ui.shopping.HouseholdContextDialog.show(this@BudgetFragment) },
                    initials = profile.displayName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() },
                    rangeLabels = listOf("Week", "Month", "30 Days"), selectedRange = displayedRange.ordinal,
                    onRangeSelected = { index -> binding.chipGroupBudgetRange.check(listOf(R.id.chip_range_week, R.id.chip_range_month, R.id.chip_range_30_days)[index]) },
                    percentLabel = percent, progress = displayedProgress / 100f,
                    remaining = remaining, remainingLabel = remainingLabel, limit = limit, spent = spent, spentLabel = spentLabel,
                    status = status, statusColor = androidx.compose.ui.graphics.Color(indicatorColor),
                    limitInput = input, limitError = inputError,
                    onLimitChange = { binding.editBudgetLimit.setText(it); binding.editBudgetLimit.error = null; inputError = null },
                    onSetLimit = {
                        binding.buttonUpdateLimit.performClick()
                        inputError = binding.editBudgetLimit.error?.toString()
                        if (inputError == null) focusManager.clearFocus()
                    },
                    onHome = { (activity as? MainActivity)?.showPantryFlow(PantryEntry.Home) },
                    onPurchaseHistory = { (activity as? MainActivity)?.openPantryAction(PantryAction.PurchaseHistory) },
                    onProfile = { if (profile.signedIn) (activity as? MainActivity)?.showProfileSettings() else (activity as? MainActivity)?.showAuthBottomSheet() },
                    chartContent = { BoundLegacyView(binding.layoutCategoryChart, Modifier.height(136.dp)) },
                    breakdownContent = { BoundLegacyView(binding.layoutCategoryBreakdown) },
                )
            }
        }
    }

    private fun setupListeners() {
        binding.chipGroupBudgetRange.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_range_week -> viewModel.setRange(BudgetRange.WEEK)
                R.id.chip_range_month -> viewModel.setRange(BudgetRange.MONTH)
                R.id.chip_range_30_days -> viewModel.setRange(BudgetRange.LAST_30_DAYS)
            }
        }

        binding.buttonUpdateLimit.setOnClickListener {
            val limitStr = binding.editBudgetLimit.text?.toString()?.trim()
            if (limitStr.isNullOrBlank()) {
                binding.editBudgetLimit.error = "Please enter a limit"
                return@setOnClickListener
            }

            val limit = limitStr.toDoubleOrNull()
            if (limit == null || !limit.isFinite() || limit < 0) {
                binding.editBudgetLimit.error = "Please enter a valid amount"
                return@setOnClickListener
            }

            viewModel.updateBudgetLimit(limit)
            binding.editBudgetLimit.text?.clear()
            binding.editBudgetLimit.clearFocus()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
            }
        }

        // Observe combined UI state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateBudgetUi(state)
                }
            }
        }

        // Observe category breakdown
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categorySpending.collect { breakdown ->
                    updateCategoryChart(breakdown)
                    updateCategoryBreakdownList(breakdown)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeRange.collect { range ->
                    updateRangeChips(range)
                }
            }
        }
    }

    private fun updateBudgetUi(state: BudgetUiState) {
        binding.textLimitValue.text = "RM ${String.format("%.2f", state.limit)}"
        binding.textSpentValue.text = "RM ${String.format("%.2f", state.periodSpent)}"
        binding.textRemainingValue.text = "RM ${String.format("%.2f", state.remaining)}"
        binding.textSpentLabel.text = state.spentLabel
        binding.textRemainingLabel.text = "Remaining this month"
        binding.textBudgetPercent.text = "${state.progressPercent}%"

        binding.progressBudget.progress = state.progressPercent
        binding.progressBudgetCircle.progress = state.progressPercent

        val statusColorRes = when {
            state.limit > 0 && state.monthlySpent > state.limit -> R.color.alert_red
            state.progressPercent >= 80 -> R.color.premium_amber
            else -> R.color.emerald
        }
        val statusColor = ContextCompat.getColor(requireContext(), statusColorRes)
        binding.progressBudget.setIndicatorColor(statusColor)
        binding.progressBudgetCircle.setIndicatorColor(statusColor)
        binding.textSpentValue.setTextColor(statusColor)
        binding.textRemainingValue.setTextColor(
            ContextCompat.getColor(requireContext(), if (statusColorRes == R.color.alert_red) R.color.alert_red else R.color.brand_forest_dark)
        )
        binding.layoutSpentCard.setBackgroundResource(
            if (statusColorRes == R.color.emerald) R.drawable.bg_spent_safe_panel else R.drawable.bg_spent_warning_panel
        )
        displayedBudgetColor = binding.textSpentValue.currentTextColor
        displayedProgress = binding.progressBudgetCircle.progress
    }

    private fun updateRangeChips(range: BudgetRange) {
        setChipSelected(binding.chipRangeWeek, range == BudgetRange.WEEK)
        setChipSelected(binding.chipRangeMonth, range == BudgetRange.MONTH)
        setChipSelected(binding.chipRange30Days, range == BudgetRange.LAST_30_DAYS)
        displayedRange = range
    }

    private fun setChipSelected(chip: Chip, selected: Boolean) {
        val backgroundColor = if (selected) R.color.m3_primary_container else R.color.m3_surface_container
        val strokeColor = if (selected) R.color.primary else R.color.m3_outline_variant
        val textColor = if (selected) R.color.m3_on_primary_container else R.color.text_secondary

        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), backgroundColor))
        chip.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), strokeColor))
        chip.setTextColor(ContextCompat.getColor(requireContext(), textColor))
    }

    private fun updateCategoryChart(breakdown: List<CategorySpend>) {
        binding.layoutCategoryChart.removeAllViews()

        if (breakdown.isEmpty()) {
            binding.textChartCaption.text = "Category spending - no purchases yet"
            val placeholderCategories = com.xmum.smartgrocery.utils.GroceryCategories.main
            binding.layoutCategoryChart.weightSum = placeholderCategories.size.toFloat()
            placeholderCategories.forEach { category ->
                addChartBar(
                    label = category,
                    amount = 0.0,
                    heightDp = 8,
                    color = R.color.m3_surface_container_high,
                    isPlaceholder = true
                )
            }
            return
        }

        binding.textChartCaption.text = "Category spending"
        val topCategories = breakdown.take(6)
        binding.layoutCategoryChart.weightSum = topCategories.size.toFloat()
        val maxSpend = topCategories.maxOfOrNull { it.categoryTotal } ?: 1.0
        topCategories.forEachIndexed { index, item ->
            val heightDp = ((item.categoryTotal / maxSpend) * 72).toInt().coerceAtLeast(18)
            addChartBar(
                label = displayCategoryLabel(item.category),
                amount = item.categoryTotal,
                heightDp = heightDp,
                color = chartColorForCategory(item.category, index)
            )
        }
    }

    private fun addChartBar(
        label: String,
        amount: Double,
        heightDp: Int,
        color: Int,
        isPlaceholder: Boolean = false
    ) {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            contentDescription = "$label spending RM ${String.format("%.2f", amount)}"
            setOnClickListener {
                Toast.makeText(
                    requireContext(),
                    "$label: RM ${String.format("%.2f", amount)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val amountLabel = TextView(requireContext()).apply {
            text = "RM ${String.format("%.2f", amount)}"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 9f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(16)
            )
        }

        val bar = View(requireContext()).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(6f, 6f, 6f, 6f, 0f, 0f, 0f, 0f).map { dp(it.toInt()).toFloat() }.toFloatArray()
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    ContextCompat.getColor(requireContext(), color),
                    ContextCompat.getColor(requireContext(), color)
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
            ).apply {
                topMargin = dp(3)
            }
        }

        val categoryLabel = TextView(requireContext()).apply {
            text = label
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textSize = 9f
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply {
                topMargin = dp(4)
            }
        }

        column.addView(amountLabel)
        column.addView(bar)
        column.addView(categoryLabel)
        binding.layoutCategoryChart.addView(column)
    }

    private fun updateCategoryBreakdownList(breakdown: List<CategorySpend>) {
        binding.layoutCategoryBreakdown.removeAllViews()

        if (breakdown.isEmpty()) {
            // Show a simple placeholder if empty
            val inflater = LayoutInflater.from(requireContext())
            val view = inflater.inflate(android.R.layout.simple_list_item_1, binding.layoutCategoryBreakdown, false)
            view.findViewById<android.widget.TextView>(android.R.id.text1).apply {
                text = "No category spending recorded yet."
                textSize = 14f
                setTextColor(resources.getColor(com.xmum.smartgrocery.R.color.text_secondary, null))
            }
            binding.layoutCategoryBreakdown.addView(view)
            return
        }

        // Find max spent to normalize progress bars relative to the largest purchase category
        val maxSpend = breakdown.maxOfOrNull { it.categoryTotal } ?: 1.0

        for ((index, item) in breakdown.withIndex()) {
            val itemBinding = ItemCategorySpendBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.layoutCategoryBreakdown,
                false
            )

            itemBinding.textCategoryName.text = item.category
            val category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(item.category)
            itemBinding.textCategoryName.setTextColor(category.foreground.toInt())
            itemBinding.textCategoryName.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(category.background.toInt())
            }
            itemBinding.textCategoryName.setPadding(dp(8), dp(5), dp(8), dp(5))
            itemBinding.textCategoryAmount.text = "RM ${String.format("%.2f", item.categoryTotal)}"

            // Percent relative to the max spending category to make progress bar scaling visually intuitive
            val percent = ((item.categoryTotal / maxSpend) * 100).toInt()
            itemBinding.progressCategory.progress = percent
            val color = ContextCompat.getColor(requireContext(), chartColorForCategory(item.category, index))
            itemBinding.progressCategory.progressTintList = ColorStateList.valueOf(color)
            itemBinding.progressCategory.progressBackgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.m3_surface_container_high))
            itemBinding.textCategoryAmount.setTextColor(color)

            binding.layoutCategoryBreakdown.addView(itemBinding.root)
        }
    }

    private fun displayCategoryLabel(category: String): String {
        return when {
            category.equals("Fruits & Vegetables", ignoreCase = true) -> "Produce"
            category.equals("Meat & Seafood", ignoreCase = true) -> "Meat"
            else -> category
        }
    }

    private fun chartColorForCategory(category: String, index: Int): Int = when (
        com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(category)) {
        com.xmum.smartgrocery.utils.Category.FOOD_BEVERAGE -> R.color.category_food_text
        com.xmum.smartgrocery.utils.Category.HOUSEHOLD_ESSENTIALS -> R.color.category_household_text
        com.xmum.smartgrocery.utils.Category.PERSONAL_CARE -> R.color.category_personal_text
    }

    private fun lighten(color: Int, amount: Float): Int {
        val red = android.graphics.Color.red(color)
        val green = android.graphics.Color.green(color)
        val blue = android.graphics.Color.blue(color)
        return android.graphics.Color.rgb(
            (red + ((255 - red) * amount)).toInt(),
            (green + ((255 - green) * amount)).toInt(),
            (blue + ((255 - blue) * amount)).toInt()
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { saveLegacyUiState(it.root, outState) }
    }
}

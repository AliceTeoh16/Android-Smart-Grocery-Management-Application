package com.xmum.smartgrocery.ui.recipes

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalFocusManager
import com.xmum.smartgrocery.ui.pantrypal.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.databinding.FragmentRecipesBinding
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.launch

class RecipeFragment : Fragment() {

    private var _binding: FragmentRecipesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RecipeViewModel by activityViewModels()
    private lateinit var adapter: RecipeAdapter
    private var handledAutoGenerate = false
    private var searchHasFocus = false
    private var displayedChecklistState by mutableStateOf(ChecklistUiState())
    private var renderedIngredients: List<com.xmum.smartgrocery.data.local.entity.GroceryItem>? = null
    private var renderedWarningDays = -1
    private var renderedIngredientDay = 0L
    private var renderedSearchHistory: List<String>? = null

    companion object {
        private const val ARG_AUTO_GENERATE = "auto_generate"
        private const val ARG_VOICE_PROMPT = "voice_prompt"

        fun newInstance(autoGenerate: Boolean = false, voicePrompt: String? = null): RecipeFragment {
            return RecipeFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_AUTO_GENERATE, autoGenerate)
                    putString(ARG_VOICE_PROMPT, voicePrompt)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecipesBinding.inflate(inflater, container, false)
        return ComposeView(requireContext()).apply {
            id = R.id.ai_checklist_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        loadHeroImage()
        setupListeners()
        restoreLegacyUiState(binding.root, savedInstanceState)
        observeViewModel()
        (view as ComposeView).setContent {
            val prompt by rememberBoundText(binding.editRecipeSearch)
            val addText by rememberBoundText(binding.buttonAddSelectedToShopping)
            var inputFocused by remember { mutableStateOf(false) }
            val focusManager = LocalFocusManager.current
            PantryPalTheme {
                AiChecklistScreen(
                    state = displayedChecklistState, prompt = prompt,
                    householdName = getString(R.string.shared_household_badge),
                    onPromptChange = { binding.editRecipeSearch.setText(it) },
                    onFocusChanged = { focused ->
                        binding.editRecipeSearch.onFocusChangeListener?.onFocusChange(binding.editRecipeSearch, focused)
                        inputFocused = focused
                    },
                    onGenerate = { binding.buttonGenerateAiRecipes.performClick() },
                    onSubmit = { focusManager.clearFocus(); binding.editRecipeSearch.onEditorAction(EditorInfo.IME_ACTION_SEARCH) },
                    onHistory = { binding.buttonChecklistHistory.performClick() },
                    onHome = { (activity as? MainActivity)?.showPantryFlow(PantryEntry.Home) },
                    showAdd = binding.buttonAddSelectedToShopping.visibility == View.VISIBLE,
                    canAdd = binding.buttonAddSelectedToShopping.isEnabled, addText = addText,
                    onAddSelected = { binding.buttonAddSelectedToShopping.performClick() },
                    showMarkCooked = binding.buttonMarkCooked.visibility == View.VISIBLE,
                    canMarkCooked = binding.buttonMarkCooked.isEnabled,
                    onMarkAsCooked = { binding.buttonMarkCooked.performClick() },
                    onVoice = { binding.fabVoiceRecipes.performClick() },
                    showSearchHistory = inputFocused && binding.layoutSearchHistory.visibility == View.VISIBLE,
                    historyContent = { BoundLegacyView(binding.scrollSearchHistory, Modifier.clipToBounds()) },
                    ingredientContent = { BoundLegacyView(binding.recyclerViewRecipes) },
                    pantryContent = { BoundLegacyView(binding.scrollPantryIngredients, Modifier.clipToBounds()) },
                    onInventorySelectionChanged = viewModel::setInventoryItemSelected,
                )
            }
        }
    }

    private fun loadHeroImage() {
        Glide.with(this)
            .load("https://images.unsplash.com/photo-1556911220-bff31c812dba?w=900&auto=format&fit=crop")
            .centerCrop()
            .into(binding.imageAiHero)
    }

    private fun setupRecyclerView() {
        adapter = RecipeAdapter { item, selected ->
            viewModel.setChecklistItemSelected(item, selected)
        }
        binding.recyclerViewRecipes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewRecipes.adapter = adapter
    }

    private fun setupListeners() {
        binding.editRecipeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setPrompt(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.editRecipeSearch.setOnFocusChangeListener { _, hasFocus ->
            searchHasFocus = hasFocus
            updateSearchHistory(viewModel.uiState.value)
        }

        binding.buttonChecklistHistory.setOnClickListener {
            ChecklistHistoryModal.newInstance().show(childFragmentManager, ChecklistHistoryModal.TAG)
        }

        binding.buttonGenerateAiRecipes.setOnClickListener {
            viewModel.generateChecklist()
        }

        binding.buttonAddSelectedToShopping.setOnClickListener {
            showShoppingTargetDialog()
        }

        binding.buttonMarkCooked.setOnClickListener {
            viewModel.markActiveChecklistCooked()
        }

        binding.fabVoiceRecipes.setOnClickListener {
            (activity as? MainActivity)?.triggerVoiceInput()
        }

        binding.editRecipeSearch.setOnEditorActionListener { _, actionId, event ->
            val isSubmitAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN

            if (isSubmitAction || isEnterKey) {
                submitChecklistSearch()
                true
            } else {
                false
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        // Restoring history hydrates the prompt as well as the recipe/checklist state.
                        if (binding.editRecipeSearch.text?.toString() != state.userPrompt) {
                            binding.editRecipeSearch.setText(state.userPrompt)
                            binding.editRecipeSearch.setSelection(state.userPrompt.length)
                        }
                        updateIngredientChips(state)
                        updateSearchHistory(state)
                        binding.progressGenerateRecipes.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                        binding.buttonGenerateAiRecipes.isEnabled = !state.isLoading
                        binding.buttonGenerateAiRecipes.text = if (state.isLoading) {
                            "Checking..."
                        } else {
                            "Generate Checklist"
                        }
                        updateChecklistResult(state)
                        maybeRunVoiceAutoGenerate(state)
                    }
                }
                launch {
                    viewModel.shoppingAddEvents.collect { message ->
                        Toast.makeText(
                            requireContext(),
                            message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateIngredientChips(state: ChecklistUiState) {
        val today = DateUtils.getStartOfToday()
        if (renderedIngredients == state.availableIngredients && renderedWarningDays == state.expiryWarningDays &&
            renderedIngredientDay == today) return
        renderedIngredients = state.availableIngredients
        renderedWarningDays = state.expiryWarningDays
        renderedIngredientDay = today
        binding.chipGroupIngredients.removeAllViews()

        if (state.availableIngredients.isEmpty()) {
            binding.chipGroupIngredients.addView(createIngredientChip("No stock yet", false))
            return
        }

        state.availableIngredients.forEach { item ->
            val isExpiringSoon = DateUtils.isExpiringWithin(item.expiryDate, state.expiryWarningDays)
            val chipText = if (isExpiringSoon) {
                "${item.name} - Expiring"
            } else {
                "${item.name} x${item.quantity}"
            }
            binding.chipGroupIngredients.addView(createIngredientChip(chipText, isExpiringSoon))
        }
    }

    private fun createIngredientChip(text: String, highlighted: Boolean): Chip {
        return Chip(requireContext()).apply {
            this.text = text
            isCheckable = false
            setTextColor(
                resources.getColor(
                    if (highlighted) R.color.expiring_chip_text else R.color.ingredient_chip_text,
                    null
                )
            )
            chipBackgroundColor = ColorStateList.valueOf(
                resources.getColor(
                    if (highlighted) R.color.expiring_chip_bg else R.color.ingredient_chip_bg,
                    null
                )
            )
            chipStrokeColor = ColorStateList.valueOf(
                resources.getColor(
                    if (highlighted) R.color.harvest_gold else R.color.primary,
                    null
                )
            )
            chipStrokeWidth = 1f
        }
    }

    private fun updateSearchHistory(state: ChecklistUiState) {
        val shouldShow = searchHasFocus && state.searchHistory.isNotEmpty()
        binding.layoutSearchHistory.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) return
        if (renderedSearchHistory == state.searchHistory) return
        renderedSearchHistory = state.searchHistory
        binding.chipGroupSearchHistory.removeAllViews()

        state.searchHistory.forEach { query ->
            binding.chipGroupSearchHistory.addView(createHistoryChip(query))
        }
    }

    private fun createHistoryChip(query: String): Chip {
        return Chip(requireContext()).apply {
            text = query
            isCheckable = false
            setTextColor(resources.getColor(R.color.text_primary, null))
            chipBackgroundColor = ColorStateList.valueOf(resources.getColor(R.color.m3_surface_container, null))
            chipStrokeColor = ColorStateList.valueOf(resources.getColor(R.color.primary, null))
            chipStrokeWidth = 1f
            setOnClickListener {
                binding.editRecipeSearch.setText(query)
                binding.editRecipeSearch.setSelection(query.length)
                binding.editRecipeSearch.clearFocus()
                searchHasFocus = false
                binding.layoutSearchHistory.visibility = View.GONE
                viewModel.generateChecklist(query)
            }
        }
    }

    private fun updateChecklistResult(state: ChecklistUiState) {
        val result = state.checklistResult
        val missingItems = result?.missingChecklist.orEmpty()
        val isPending = state.activeChecklistStatus == "pending"
        adapter.submitList(state.selectableMissingItems)

        binding.textDishSummary.visibility = if (result == null) View.GONE else View.VISIBLE
        binding.textAlreadyHave.visibility = if (result == null) View.GONE else View.VISIBLE

        if (result != null) {
            binding.textDishSummary.text = "${result.dishName} - ${result.servings} serving(s)"
            binding.textAlreadyHave.text = if (result.alreadyHave.isEmpty()) {
                "Matched stock: No matching inventory items found yet."
            } else {
                "Already in stock: ${result.alreadyHave.joinToString(", ")}"
            }
        }

        val showAddButton = result != null && (missingItems.isNotEmpty() || state.selectableInventoryItems.isNotEmpty()) && state.generationError == null
        binding.buttonAddSelectedToShopping.visibility = if (showAddButton && isPending) View.VISIBLE else View.GONE
        binding.buttonAddSelectedToShopping.isEnabled = showAddButton && isPending && state.selectedShoppingCount > 0 && !state.isLoading && !state.isAddingToShopping
        binding.buttonAddSelectedToShopping.text = if (state.selectedShoppingCount > 0) {
            "Add Selected (${state.selectedShoppingCount}) to Shopping List"
        } else {
            "Select items to add"
        }
        binding.buttonMarkCooked.visibility = if (result != null && isPending) View.VISIBLE else View.GONE
        binding.buttonMarkCooked.isEnabled = result != null && isPending && !state.isLoading && !state.isAddingToShopping

        if (!state.hasGenerated || missingItems.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.recyclerViewRecipes.visibility = View.GONE
            binding.textEmptyState.text = when {
                state.generationError != null -> "Checklist failed"
                result != null && missingItems.isEmpty() -> "No missing items"
                else -> "Ready to check"
            }
            binding.textEmptyStateDetail.text = when {
                state.generationError != null -> state.generationError
                result != null && missingItems.isEmpty() -> "Based on the assistant response, your current stock covers the requested dish."
                else -> "Type a dish request like: Tomato Beef Noodles for 4 people."
            }
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.recyclerViewRecipes.visibility = View.VISIBLE
        }
        displayedChecklistState = state
    }

    private fun showShoppingTargetDialog() {
        val options = arrayOf(
            "Personal Shopping List",
            "Household / Shared Shopping List"
        )
        val density = resources.displayMetrics.density
        val title = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (12 * density).toInt())
            addView(android.widget.TextView(context).apply {
                text = "Add selected items to"; textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFF315B40.toInt())
            })
            if (viewModel.uiState.value.hasSelectedItemsInInventory) addView(android.widget.TextView(context).apply {
                text = "* Note: Some selected items are already in stock."
                textSize = 12f; setTextColor(0xFF7B8A7C.toInt()); setPadding(0, (10 * density).toInt(), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(title)
            .setItems(options) { _, which ->
                val assignedTo = if (which == 0) "Me" else "Household"
                viewModel.addSelectedMissingItemsToShoppingList(assignedTo)
            }
            .show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFF9F8F3.toInt()); cornerRadius = 24 * density
        })
    }

    private fun submitChecklistSearch() {
        binding.editRecipeSearch.clearFocus()
        searchHasFocus = false
        binding.layoutSearchHistory.visibility = View.GONE
        viewModel.generateChecklist()
    }

    private fun maybeRunVoiceAutoGenerate(state: ChecklistUiState) {
        val shouldAutoGenerate = arguments?.getBoolean(ARG_AUTO_GENERATE) == true
        val voicePrompt = arguments?.getString(ARG_VOICE_PROMPT).orEmpty()
        if (!shouldAutoGenerate || handledAutoGenerate || voicePrompt.isBlank()) {
            return
        }

        handledAutoGenerate = true
        binding.editRecipeSearch.setText(voicePrompt)
        viewModel.generateChecklist(voicePrompt)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderedIngredients = null
        renderedSearchHistory = null
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { saveLegacyUiState(it.root, outState) }
    }
}


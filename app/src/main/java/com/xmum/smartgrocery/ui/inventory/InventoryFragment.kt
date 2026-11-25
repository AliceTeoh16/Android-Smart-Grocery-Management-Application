package com.xmum.smartgrocery.ui.inventory

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.xmum.smartgrocery.ui.pantrypal.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xmum.smartgrocery.utils.Category
import com.xmum.smartgrocery.utils.FoodSubcategory
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.button.MaterialButton
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.ui.auth.AuthBottomSheetDialog
import com.xmum.smartgrocery.databinding.FragmentInventoryBinding
import com.xmum.smartgrocery.databinding.DialogDeleteItemConfirmationBinding
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.shopping.HouseholdContextDialog
import kotlinx.coroutines.launch

class InventoryFragment : Fragment() {

    private var _binding: FragmentInventoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InventoryViewModel by viewModels()
    private lateinit var adapter: InventoryAdapter
    private var featureNavController: NavHostController? = null
    private var displayedInventoryFilter by mutableStateOf(InventoryFilter.ALL_IN_STOCK)

    private val authPrefs by lazy {
        requireContext().getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val AUTH_PREFS_NAME = "smart_inventory_auth"
        private const val PREF_SIGNED_IN = "is_signed_in"
        private const val PREF_EMAIL = "email"
        private const val PREF_DISPLAY_NAME = "display_name"
        private const val ARG_FILTER = "initial_inventory_filter"
        private const val STATE_CATEGORY_FILTER = "inventory_category_filter"
        private const val STATE_SUBCATEGORY_FILTER = "inventory_subcategory_filter"

        fun newInstance(filter: InventoryFilter) = InventoryFragment().apply {
            arguments = Bundle().apply { putString(ARG_FILTER, filter.name) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInventoryBinding.inflate(inflater, container, false)
        return ComposeView(requireContext()).apply {
            id = R.id.inventory_feature_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (savedInstanceState?.getString(ARG_FILTER) ?: arguments?.getString(ARG_FILTER))?.let { name ->
            runCatching { InventoryFilter.valueOf(name) }.getOrNull()?.let(viewModel::setInventoryFilter)
        }
        if (savedInstanceState?.containsKey(STATE_CATEGORY_FILTER) == true) {
            viewModel.setCategoryFilter(savedInstanceState.getString(STATE_CATEGORY_FILTER)?.let { name ->
                runCatching { Category.valueOf(name) }.getOrNull()
            })
            viewModel.setSubcategoryFilter(savedInstanceState.getString(STATE_SUBCATEGORY_FILTER)?.let { name ->
                runCatching { FoodSubcategory.valueOf(name) }.getOrNull()
            })
        }

        setupRecyclerView()
        setupSearch()
        setupListeners()
        setupAuthResultListener()
        updateSharedUi()
        restoreLegacyUiState(binding.root, savedInstanceState)
        observeViewModel()
        (view as ComposeView).setContent {
            val navController = rememberNavController()
            DisposableEffect(navController) {
                featureNavController = navController
                onDispose { featureNavController = null }
            }
            PantryPalTheme {
                InventoryFeatureNavHost(
                    navController = navController, inventoryView = binding.root, fragmentManager = childFragmentManager,
                    onFullScreen = { fullScreen -> (activity as? MainActivity)?.setFeatureFullScreen(fullScreen) },
                    inventoryContent = { InventoryPage() },
                )
            }
        }
    }

    @Composable
    private fun InventoryPage() {
        val app = requireActivity().application as com.xmum.smartgrocery.SmartGroceryApp
        val dataContext by app.sessionManager.context.collectAsStateWithLifecycle(initialValue = null)
        val session by app.sessionManager.sessions.collectAsStateWithLifecycle(initialValue = null)
        val initials by rememberBoundText(binding.textProfileAvatar)
        val search by rememberBoundText(binding.editSearch)
        val itemCount by rememberInventoryRowCount(adapter)
        val selectedCategory by viewModel.categoryFilter.collectAsStateWithLifecycle()
        val selectedSubcategory by viewModel.subcategoryFilter.collectAsStateWithLifecycle()
        InventoryScreen(
            onReviewRestock = { (activity as? androidx.appcompat.app.AppCompatActivity)?.let(com.xmum.smartgrocery.ui.inventory.RestockReviewActivity::open) },
            initials = initials,
            scopeLabels = listOf("Personal", "Household"),
            selectedScope = if (session?.householdId == null) 0 else 1,
            search = search, searchHint = binding.editSearch.hint.toString(),
            filterLabels = listOf(binding.buttonFilterActive.text.toString(), binding.buttonFilterExpiring.text.toString(), binding.buttonFilterExpired.text.toString()),
            selectedFilter = displayedInventoryFilter.ordinal, itemCount = itemCount,
            selectedCategory = selectedCategory, onCategorySelected = viewModel::setCategoryFilter,
            selectedSubcategory = selectedSubcategory, onSubcategorySelected = viewModel::setSubcategoryFilter,
            showClearExpired = binding.buttonClearExpired.visibility == View.VISIBLE,
            onSearchChange = { binding.editSearch.setText(it) },
            onFilterSelected = { index ->
                listOf(binding.buttonFilterActive, binding.buttonFilterExpiring, binding.buttonFilterExpired)[index].performClick()
            },
            onScopeSelected = { index ->
                if (index == 1 && dataContext?.memberCount == 0) {
                    HouseholdContextDialog.show(this@InventoryFragment)
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            app.sessionManager.selectScope(index == 1)
                        } catch (failure: Exception) {
                            Toast.makeText(
                                requireContext(),
                                failure.message ?: "Unable to switch inventory scope.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            onShareConnect = { (activity as? MainActivity)?.openPantryAction(PantryAction.Household) },
            onHome = { (activity as? MainActivity)?.showPantryFlow(PantryEntry.Home) },
            onProfile = { binding.textProfileAvatar.performClick() },
            onVoice = { binding.fabVoiceInventory.performClick() },
            onAddItem = { binding.fabAddInventory.performClick() },
            onClearExpired = { binding.buttonClearExpired.performClick() },
            inventoryContent = { BoundLegacyView(binding.recyclerViewInventory) },
        )
    }

    private fun setupRecyclerView() {
        adapter = InventoryAdapter(
            onIncrement = { item -> viewModel.incrementQuantity(item) },
            onDecrement = { item -> viewModel.decrementQuantity(item) },
            onEdit = { item -> (activity as? MainActivity)?.showEditItemBottomSheet(item) },
            onItemLongClick = { item -> showDeleteConfirmationDialog(item) },
            onShowBatches = { item -> showBatchBreakdown(item) },
            onToss = { item -> showTossConfirmationDialog(item) },
            onUpdateExpiry = { item -> (activity as? MainActivity)?.showEditItemBottomSheet(item) }
        )
        binding.recyclerViewInventory.layoutManager =
            GridLayoutManager(requireContext(), 2)
        binding.recyclerViewInventory.itemAnimator = null
        binding.recyclerViewInventory.adapter = adapter
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupListeners() {
        binding.buttonMicLarge.setOnClickListener {
            (activity as? MainActivity)?.triggerVoiceInput()
        }

        binding.fabVoiceInventory.setOnClickListener {
            (activity as? MainActivity)?.triggerVoiceInput()
        }

        binding.fabAddInventory.setOnClickListener {
            featureNavController?.navigate(ADD_GROCERY_ROUTE) { launchSingleTop = true }
        }

        binding.buttonFilterActive.setOnClickListener {
            viewModel.setInventoryFilter(InventoryFilter.ALL_IN_STOCK)
        }
        binding.buttonFilterExpiring.setOnClickListener {
            viewModel.setInventoryFilter(InventoryFilter.EXPIRING_SOON)
        }
        binding.buttonFilterExpired.setOnClickListener {
            viewModel.setInventoryFilter(InventoryFilter.EXPIRED)
        }
        binding.buttonClearExpired.setOnClickListener {
            val content = DialogDeleteItemConfirmationBinding.inflate(layoutInflater)
            content.textDeleteItemTitle.text = "Clear Expired Items"
            content.textDeleteItemName.text = "Expired inventory"
            content.textDeleteItemMessage.text = "Delete all expired inventory batches?"
            content.buttonConfirmDeleteItem.text = "Clear"
            val dialog = AlertDialog.Builder(requireContext())
                .setView(content.root)
                .setPositiveButton("Clear") { _, _ ->
                    viewModel.clearExpiredItems()
                    Toast.makeText(requireContext(), "Expired items cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            styleDeleteItemDialog(dialog, content)
        }

        binding.textProfileAvatar.setOnClickListener {
            if (isSignedIn()) {
                (activity as? MainActivity)?.showProfileSettings()
            } else {
                (activity as? MainActivity)?.showAuthBottomSheet()
            }
        }

        binding.buttonInvite.setOnClickListener {
            if (isSignedIn()) {
                com.xmum.smartgrocery.ui.shopping.HouseholdContextDialog.show(this@InventoryFragment)
            } else {
                (activity as? MainActivity)?.showAuthBottomSheet()
            }
        }

        binding.cardSharedConnect.setOnClickListener {
            if (isSignedIn()) {
                (activity as? MainActivity)?.openPantryAction(PantryAction.Household)
            } else {
                (activity as? MainActivity)?.showAuthBottomSheet()
            }
        }
    }

    private fun setupAuthResultListener() {
        parentFragmentManager.setFragmentResultListener(
            AuthBottomSheetDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            updateSharedUi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateSharedUi()
        }
    }

    private fun updateSharedUi() {
        val signedIn = isSignedIn()
        val displayName = authPrefs.getString(PREF_DISPLAY_NAME, null)
        val email = authPrefs.getString(PREF_EMAIL, null)
        val initials = initialsFor(displayName ?: email)

        binding.textProfileAvatar.text = if (signedIn) initials else "?"
        binding.textProfileAvatar.setTextColor(
            requireContext().getColor(R.color.m3_on_primary_container)
        )
        binding.textProfileAvatar.setBackgroundResource(
            if (signedIn) R.drawable.bg_profile_avatar else R.drawable.bg_profile_avatar_signed_out
        )

        binding.textHouseholdBadge.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.textSharedTitle.text = if (signedIn) {
            getString(R.string.shared_connect_title)
        } else {
            getString(R.string.shared_inventory_login_prompt)
        }
        binding.textSharedSubtitle.text = if (signedIn) {
            getString(R.string.shared_household_badge) + " • 3 members"
        } else {
            getString(R.string.shared_connect_signed_out)
        }
        if (signedIn) {
            binding.textSharedSubtitle.text = getString(R.string.shared_household_badge) + " \u2022 3 members"
        }
        binding.textSharedSubtitle.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.buttonInvite.text = if (signedIn) {
            getString(R.string.shared_invite_button)
        } else {
            getString(R.string.shared_login_button)
        }

        val collaborationVisibility = if (signedIn) View.VISIBLE else View.GONE
        binding.layoutSharedMembers.visibility = collaborationVisibility
    }

    private fun isSignedIn(): Boolean {
        return authPrefs.getBoolean(PREF_SIGNED_IN, false)
    }

    private fun initialsFor(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return "SI"

        val nameParts = text
            .substringBefore("@")
            .split('.', '_', '-', ' ')
            .filter { it.isNotBlank() }

        return nameParts
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "SI" }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.inventoryItems.collect { items ->
                    adapter.submitList(items)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.expiryWarningDays.collect { days ->
                    adapter.setExpiryWarningDays(days)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.inventoryFilter.collect { filter ->
                    updateInventoryFilterButtons(filter)
                }
            }
        }
    }

    private fun updateInventoryFilterButtons(filter: InventoryFilter) {
        styleInventoryFilterButton(
            binding.buttonFilterActive,
            filter == InventoryFilter.ALL_IN_STOCK
        )
        styleInventoryFilterButton(
            binding.buttonFilterExpiring,
            filter == InventoryFilter.EXPIRING_SOON
        )
        styleInventoryFilterButton(
            binding.buttonFilterExpired,
            filter == InventoryFilter.EXPIRED
        )
        binding.buttonClearExpired.visibility = if (filter == InventoryFilter.EXPIRED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        displayedInventoryFilter = filter
    }

    private fun styleInventoryFilterButton(button: MaterialButton, selected: Boolean) {
        val background = if (selected) R.color.m3_primary_container else R.color.surface
        val text = if (selected) R.color.primary else R.color.text_secondary
        val stroke = if (selected) R.color.primary else R.color.m3_outline_variant
        button.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(background))
        button.strokeColor = ColorStateList.valueOf(requireContext().getColor(stroke))
        button.strokeWidth = dp(1)
        button.setTextColor(requireContext().getColor(text))
    }

    private fun showDeleteConfirmationDialog(item: InventoryCardItem) {
        val content = DialogDeleteItemConfirmationBinding.inflate(layoutInflater)
        content.textDeleteItemName.text = item.item.name
        content.textDeleteItemMessage.text = "Are you sure you want to remove all ${item.item.name} batches from your inventory?"
        val dialog = AlertDialog.Builder(requireContext())
            .setView(content.root)
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteItem(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
        styleDeleteItemDialog(dialog, content)
    }

    private fun showTossConfirmationDialog(item: InventoryCardItem) {
        val content = DialogDeleteItemConfirmationBinding.inflate(layoutInflater)
        content.textDeleteItemTitle.text = "Toss Expired Item"
        content.textDeleteItemName.text = item.item.name
        content.textDeleteItemMessage.text = "Remove all expired ${item.item.name} batches from inventory?"
        content.buttonConfirmDeleteItem.text = "Toss"
        val dialog = AlertDialog.Builder(requireContext())
            .setView(content.root)
            .setPositiveButton("Toss") { _, _ ->
                viewModel.deleteItem(item)
                Toast.makeText(requireContext(), "${item.item.name} discarded", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
        styleDeleteItemDialog(dialog, content)
    }

    private fun showBatchBreakdown(card: InventoryCardItem) {
        val message = buildString {
            append("Total quantity: ${card.totalQuantity} ${card.item.unit}\n")
            append("Storage: ${card.item.storageLocation}\n\n")
            card.batches.forEachIndexed { index, batch ->
                append("Batch ${index + 1}: ${batch.quantity} ${card.item.unit}, Exp ${com.xmum.smartgrocery.utils.DateUtils.formatDate(batch.expiryDate)}\n")
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("${card.item.name} Batches")
            .setMessage(message.trim())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ARG_FILTER, viewModel.inventoryFilter.value.name)
        outState.putString(STATE_CATEGORY_FILTER, viewModel.categoryFilter.value?.name)
        outState.putString(STATE_SUBCATEGORY_FILTER, viewModel.subcategoryFilter.value?.name)
        _binding?.let { saveLegacyUiState(it.root, outState) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.xmum.smartgrocery.ui.shopping

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.pantrypal.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.repository.AppPreferencesRepository
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.databinding.FragmentShoppingListBinding
import com.xmum.smartgrocery.databinding.DialogShoppingLinkQrBinding
import com.xmum.smartgrocery.ui.auth.AuthBottomSheetDialog
import kotlinx.coroutines.launch

class ShoppingListFragment : Fragment() {

    private var _binding: FragmentShoppingListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShoppingListViewModel by viewModels()
    private val householdViewModel: HouseholdViewModel by viewModels()
    private lateinit var adapter: ShoppingListAdapter
    private var currentMemberFilter = ShoppingMemberFilter.PERSONAL
    private var featureNavController: NavHostController? = null
    private var displayedMemberFilter by mutableStateOf(ShoppingMemberFilter.PERSONAL)

    private val authPrefs by lazy {
        requireContext().getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
    }


    companion object {
        private const val AUTH_PREFS_NAME = "smart_inventory_auth"
        private const val PREF_SIGNED_IN = "is_signed_in"
        private const val ARG_OPEN_HOUSEHOLD = "open_household"
        private const val ARG_RETURN_NAVIGATION_ITEM = "return_navigation_item"
        private const val NO_RETURN_NAVIGATION_ITEM = -1

        fun newInstance(openHousehold: Boolean, returnNavigationItemId: Int? = null) = ShoppingListFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_OPEN_HOUSEHOLD, openHousehold)
                returnNavigationItemId?.let { putInt(ARG_RETURN_NAVIGATION_ITEM, it) }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShoppingListBinding.inflate(inflater, container, false)
        return ComposeView(requireContext()).apply {
            id = R.id.shopping_feature_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val navController = rememberNavController()
                DisposableEffect(navController) {
                    featureNavController = navController
                    onDispose { featureNavController = null }
                }
                PantryPalTheme {
                    NavHost(navController, startDestination = "shopping") {
                        composable("shopping") {
                            ShoppingPage()
                        }
                        composable("shopping/household") {
                            DisposableEffect(Unit) {
                                (activity as? MainActivity)?.setFeatureFullScreen(true)
                                onDispose { (activity as? MainActivity)?.setFeatureFullScreen(false) }
                            }
                            HouseholdPage { popHouseholdBackStack(navController) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HouseholdPage(onBack: () -> Unit) {
        val state by householdViewModel.state.collectAsStateWithLifecycle()
        val profile = remember { LocalAuthSession(requireContext()).currentProfile() }
        ShareAndConnectScreen(
            sharingContent = if (com.xmum.smartgrocery.data.sharing.SharingRuntime.forDatabase((requireActivity().application as com.xmum.smartgrocery.SmartGroceryApp).database) == null) null else { { SharingPanel(state) } },
            householdName = state.name, householdDescription = state.description, members = state.members,
            hasHousehold = state.householdId != null, inviteCode = state.inviteCode, inviteLink = state.inviteLink,
            expiryWarning = "${state.expiryWarningDays}d", canDecrease = state.expiryWarningDays > 1,
            canIncrease = state.expiryWarningDays < 30,
            onDecrement = { householdViewModel.adjustWarning(-1) }, onIncrement = { householdViewModel.adjustWarning(1) },
            onCopyCode = { copyToClipboard("Household code", state.inviteCode, "Join code copied") },
            onShareInvite = { shareHouseholdInvite() },
            onManage = { HouseholdManagementDialog.show(this@ShoppingListFragment, state) }, onBack = onBack,
            initials = profile.displayName.take(1).uppercase().ifBlank { null })
    }

    @Composable
    private fun SharingPanel(state: HouseholdUiState) {
        val runtime = com.xmum.smartgrocery.data.sharing.SharingRuntime.forDatabase((requireActivity().application as com.xmum.smartgrocery.SmartGroceryApp).database) ?: return
        val status by runtime.service.status.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        FeatureCard {
            Text(status.message)
            TextButton(onClick = { SharingSetupDialog.show(this@ShoppingListFragment) }) { Text(if (status.connected) "Reconnect account" else "Connect account") }
            if (status.connected && state.isOwner && state.householdId != null && state.sharingServer == null) {
                TextButton(onClick = { scope.launch {
                    runCatching { runtime.service.publishHousehold(state.householdId) }.onFailure {
                        Toast.makeText(requireContext(), it.message ?: "Unable to enable sharing.", Toast.LENGTH_LONG).show()
                    }
                } }) { Text("Enable cross-device sharing for this household") }
            }
            if (status.connected) TextButton(enabled = !status.syncing, onClick = { scope.launch { runCatching { runtime.service.synchronize() } } }) { Text("Sync now") }
        }
    }

    @Composable
    private fun ShoppingPage() {
        val dataContext by (requireActivity().application as com.xmum.smartgrocery.SmartGroceryApp).sessionManager.context.collectAsState(initial = null)
        val home by householdViewModel.state.collectAsStateWithLifecycle()
        val cart by rememberBoundText(binding.textBudgetCartTotal)
        val remaining by rememberBoundText(binding.textBudgetRemaining)
        val status by rememberBoundText(binding.textBudgetStatus)
        val summary by rememberBoundText(binding.textListSummary)
        val profile = remember { LocalAuthSession(requireContext()).currentProfile() }
        ShoppingListScreen(
            initials = profile.displayName.split(' ').filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercaseChar().toString() },
            householdName = home.name, householdVisible = binding.layoutHouseholdSharing.visibility == View.VISIBLE,
            memberCount = home.activeMemberCount,
            cartTotal = cart.substringAfter(": "), remainingBudget = remaining.substringAfter(": "), budgetStatus = status,
            filterLabels = listOf("Personal", "Household"), selectedFilter = displayedMemberFilter.ordinal,
            summary = summary, hasItems = binding.recyclerViewShoppingList.visibility == View.VISIBLE,
            canShare = binding.buttonShareList.isEnabled, canClear = binding.buttonClearChecked.isEnabled,
            onFilterSelected = { index -> viewModel.setMemberFilter(ShoppingMemberFilter.entries[index]) },
            onHome = { (activity as? MainActivity)?.showPantryFlow(PantryEntry.Home) },
            onPurchaseHistory = { (activity as? MainActivity)?.openPantryAction(PantryAction.PurchaseHistory) },
            onProfile = { if (profile.signedIn) (activity as? MainActivity)?.showProfileSettings() else (activity as? MainActivity)?.showAuthBottomSheet() },
            onHousehold = { binding.textHouseholdCount.performClick() },
            onShareList = { binding.buttonShareList.performClick() },
            onAddItem = { binding.fabAddShoppingItem.performClick() },
            onVoice = { binding.fabVoiceShopping.performClick() },
            onClearChecked = { binding.buttonClearChecked.performClick() },
            listContent = { BoundLegacyView(binding.recyclerViewShoppingList) },
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        setupAuthResultListener()
        updateHouseholdSharingUi()
        updateMemberFilterChips(ShoppingMemberFilter.PERSONAL)
        restoreLegacyUiState(binding.root, savedInstanceState)
        observeViewModel()
        if (savedInstanceState == null && arguments?.getBoolean(ARG_OPEN_HOUSEHOLD) == true) {
            view.post {
                if (isAdded && _binding != null) showHouseholdMembersDialog()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ShoppingListAdapter(
            onCheckedChange = { item, checked ->
                if (checked && item.estimatedUnitPrice <= 0.0) {
                    showPurchasePriceDialog(item)
                } else {
                    viewModel.setItemChecked(item, checked)
                }
            },
            onAssignClick = { item, anchor -> showAssigneeMenu(item, anchor) },
            onDelete = { item -> viewModel.deleteItem(item) }
        )
        binding.recyclerViewShoppingList.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewShoppingList.itemAnimator = null
        binding.recyclerViewShoppingList.adapter = adapter
    }

    private fun setupListeners() {
        binding.buttonDecreaseWarningDays.setOnClickListener {
            viewModel.decreaseExpiryWarningDays()
        }
        binding.buttonIncreaseWarningDays.setOnClickListener {
            viewModel.increaseExpiryWarningDays()
        }
        binding.buttonClearChecked.setOnClickListener {
            viewModel.clearCheckedItems()
        }
        binding.buttonShareList.setOnClickListener {
            showShareOptions()
        }
        binding.fabVoiceShopping.setOnClickListener {
            (activity as? com.xmum.smartgrocery.ui.main.MainActivity)?.triggerVoiceInput()
        }
        binding.fabAddShoppingItem.setOnClickListener {
            AddShoppingItemDialog.newInstance()
                .show(parentFragmentManager, "AddShoppingItemDialog")
        }
        binding.layoutMemberAvatars.setOnClickListener {
            showHouseholdMembersDialog()
        }
        binding.textHouseholdCount.setOnClickListener {
            showHouseholdMembersDialog()
        }
        binding.buttonInviteHousemates.setOnClickListener {
            showInviteHousematesSheet()
        }
        binding.chipGroupMembers.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chip_member_me -> ShoppingMemberFilter.PERSONAL
                R.id.chip_member_household -> ShoppingMemberFilter.HOUSEHOLD
                else -> ShoppingMemberFilter.PERSONAL
            }
            if (filter != viewModel.activeMemberFilter.value) viewModel.setMemberFilter(filter)
        }
    }

    private fun setupAuthResultListener() {
        parentFragmentManager.setFragmentResultListener(
            AuthBottomSheetDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            updateHouseholdSharingUi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateHouseholdSharingUi()
        }
    }

    private fun updateHouseholdSharingUi() {
        val signedIn = authPrefs.getBoolean(PREF_SIGNED_IN, false)
        binding.layoutHouseholdSharing.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.textHouseholdCount.text = householdViewModel.state.value.name
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.messages.collect { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    val actual = viewModel.activeMemberFilter.value
                    binding.chipGroupMembers.check(if (actual == ShoppingMemberFilter.PERSONAL) R.id.chip_member_me else R.id.chip_member_household)
                    updateMemberFilterChips(actual)
                } }
                launch { householdViewModel.messages.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() } }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shoppingListItems.collect { items ->
                    adapter.submitList(items)
                    val openItems = items.count { !it.isChecked }
                    binding.textListSummary.text = when (openItems) {
                        0 -> "No items to buy"
                        1 -> "1 item to buy"
                        else -> "$openItems items to buy"
                    }
                    binding.layoutEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerViewShoppingList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allShoppingListItems.collect { items ->
                    binding.buttonClearChecked.isEnabled = items.any { it.isChecked }
                    binding.buttonShareList.isEnabled = items.any { !it.isChecked }
                    updateMemberCounts(items)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.budgetImpact.collect { impact ->
                    updateBudgetImpact(impact)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeMemberFilter.collect { filter ->
                    currentMemberFilter = filter
                    val checkedId = when (filter) {
                        ShoppingMemberFilter.PERSONAL -> R.id.chip_member_me
                        ShoppingMemberFilter.HOUSEHOLD -> R.id.chip_member_household
                    }
                    if (binding.chipGroupMembers.checkedChipId != checkedId) {
                        binding.chipGroupMembers.check(checkedId)
                    }
                    updateMemberFilterChips(filter)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.expiryWarningDays.collect { days ->
                    binding.textWarningDays.text = "${days}d"
                    binding.textExpirySettingSummary.text = "Warn when items expire within $days ${if (days == 1) "day" else "days"}"
                    binding.buttonDecreaseWarningDays.isEnabled = days > AppPreferencesRepository.MIN_EXPIRY_WARNING_DAYS
                    binding.buttonIncreaseWarningDays.isEnabled = days < AppPreferencesRepository.MAX_EXPIRY_WARNING_DAYS
                }
            }
        }
    }

    private fun updateMemberCounts(items: List<ShoppingListItem>) {
        binding.chipMemberMe.text = "Personal"
        binding.chipMemberHousehold.text = "Household"
        updateMemberFilterChips(currentMemberFilter)
    }

    private fun updateBudgetImpact(impact: ShoppingBudgetImpact) {
        val currency = { amount: Double -> "RM ${String.format("%.2f", amount)}" }
        binding.textBudgetCartTotal.text = "Estimated Cart Total: ${currency(impact.cartTotal)}"
        binding.textBudgetRemaining.text = "Remaining Budget: ${currency(impact.remainingBudget)}"

        if (impact.exceedsBudget) {
            binding.cardBudgetImpact.setCardBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.premium_coral_soft)
            )
            binding.textBudgetStatus.text =
                "Warning: This shopping list exceeds your remaining budget!"
            binding.textBudgetStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.alert_red)
            )
        } else {
            binding.cardBudgetImpact.setCardBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.budget_safe_bg)
            )
            binding.textBudgetStatus.text = "Within safe budget limits"
            binding.textBudgetStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.emerald)
            )
        }
    }

    private fun updateMemberFilterChips(activeFilter: ShoppingMemberFilter) {
        currentMemberFilter = activeFilter
        styleFilterChip(binding.chipMemberMe, activeFilter == ShoppingMemberFilter.PERSONAL)
        styleFilterChip(binding.chipMemberHousehold, activeFilter == ShoppingMemberFilter.HOUSEHOLD)
        displayedMemberFilter = activeFilter
    }

    private fun styleFilterChip(chip: Chip, selected: Boolean) {
        val backgroundColor = if (selected) R.color.m3_primary_container else R.color.surface
        val textColor = if (selected) R.color.primary else R.color.text_secondary
        val strokeColor = if (selected) R.color.primary else R.color.m3_outline_variant

        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), backgroundColor))
        chip.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), strokeColor))
        chip.chipStrokeWidth = dp(1).toFloat()
        chip.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        chip.isCheckedIconVisible = selected
        chip.checkedIconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), textColor))
    }

    private fun showPurchasePriceDialog(item: ShoppingListItem) {
        val priceInput = EditText(requireContext()).apply {
            hint = "Total price paid"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        val content = ComposeView(requireContext()).apply {
            id = R.id.purchase_confirmation_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        // Platform AlertDialog windows do not inherit the Fragment's Compose owners.
        dialog.window?.decorView?.apply {
            setViewTreeLifecycleOwner(viewLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(this@ShoppingListFragment)
        }
        content.setContent {
            val price by rememberBoundText(priceInput)
            var priceError by remember { mutableStateOf(priceInput.error?.toString()) }
            PantryPalTheme {
                PurchaseConfirmationDialogContent(
                    itemName = item.itemName, price = price, priceError = priceError,
                    onPriceChange = {
                        priceInput.setText(it)
                        priceInput.error = null
                        priceError = null
                    },
                    onConfirm = {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        priceError = priceInput.error?.toString()
                    },
                    onCancel = { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() },
                )
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val price = priceInput.text?.toString()?.trim()?.toDoubleOrNull()
                if (price == null || price <= 0.0) {
                    priceInput.error = "Enter a valid price"
                    return@setOnClickListener
                }

                viewModel.completeItemWithPurchasePrice(item, price)
                Toast.makeText(requireContext(), "${item.itemName} saved to inventory", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
        // Keep the original dialog buttons and their listeners as the action owners.
        (dialog.getButton(AlertDialog.BUTTON_POSITIVE).parent as? View)?.visibility = View.GONE
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels - dp(48)).coerceAtMost(dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showAssigneeMenu(item: ShoppingListItem, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("Me")
            menu.add("Household")
            setOnMenuItemClickListener { menuItem ->
                viewModel.assignItem(item, menuItem.title.toString())
                true
            }
            show()
        }
    }

    private fun shareShoppingList() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Smart Grocery Shopping List")
            putExtra(Intent.EXTRA_TEXT, viewModel.buildShareText())
        }
        startActivity(Intent.createChooser(shareIntent, "Share shopping list"))
    }

    private fun showShareOptions() {
        val options = arrayOf(
            "Copy raw text list",
            "Generate sharing link / QR",
            "Share via WhatsApp or Messages",
            "+ Invite members / family"
        )

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Share shopping list")
            .setCustomTitle(layoutInflater.inflate(R.layout.dialog_shopping_share_header, null))
            .setAdapter(ShoppingShareOptionsAdapter(requireContext(), options)) { _, which ->
                when (which) {
                    0 -> copyToClipboard("Shopping list", viewModel.buildShareText(), "Shopping list copied")
                    1 -> showSharingLinkDialog()
                    2 -> shareShoppingList()
                    3 -> showInviteHousematesSheet()
                }
            }
            .show()
        styleShoppingShareDialog(dialog)
    }

    private fun showHouseholdMembersDialog() {
        featureNavController?.navigate("shopping/household") { launchSingleTop = true }
    }

    fun popFeatureBackStack(): Boolean {
        val navController = featureNavController ?: return false
        if (navController.currentDestination?.route != "shopping/household") return false
        return popHouseholdBackStack(navController)
    }

    private fun popHouseholdBackStack(navController: NavHostController): Boolean {
        val popped = navController.popBackStack()
        if (popped) {
            arguments?.getInt(ARG_RETURN_NAVIGATION_ITEM, NO_RETURN_NAVIGATION_ITEM)
                ?.takeIf { it != NO_RETURN_NAVIGATION_ITEM }
                ?.let { (activity as? MainActivity)?.restoreNavigation(it) }
        }
        return popped
    }


    private fun showInviteHousematesSheet() {
        HouseholdInvitationDialog.show(this)
    }

    private fun shareHouseholdInvite() {
        HouseholdInvitationDialog.share(this)
    }

    private fun showSharingLinkDialog() {
        val link = buildShoppingShareLink()
        val content = DialogShoppingLinkQrBinding.inflate(layoutInflater)
        val qrImage = content.imageShoppingShareQr.apply {
            setImageBitmap(createQrBitmap(link, dp(220)))
            adjustViewBounds = true
        }
        val linkText = content.textShoppingShareLink.apply {
            text = link
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(content.root)
            .setPositiveButton("Copy Link") { _, _ ->
                copyToClipboard("Shopping list link", link, "Sharing link copied")
            }
            .setNegativeButton("Close", null)
            .show()
        styleSharingLinkQrDialog(dialog, content)
    }

    private fun buildShoppingShareLink(): String {
        val openCount = viewModel.allShoppingListItems.value.count { !it.isChecked }
        return "smartgrocery://shopping-list/share?items=$openCount&created=${System.currentTimeMillis()}"
    }

    private fun createQrBitmap(text: String, size: Int): Bitmap {
        return HouseholdInvitationDialog.createQrBitmap(text, size)
    }

    private fun copyToClipboard(label: String, text: String, toastMessage: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), toastMessage, Toast.LENGTH_SHORT).show()
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

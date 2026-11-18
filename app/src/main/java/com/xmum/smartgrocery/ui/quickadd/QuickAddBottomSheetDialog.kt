package com.xmum.smartgrocery.ui.quickadd

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.material3.Surface
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import com.xmum.smartgrocery.ui.pantrypal.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.databinding.BottomSheetQuickAddBinding
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.xmum.smartgrocery.utils.DateUtils
import com.xmum.smartgrocery.utils.ExpiryEstimator
import java.util.Calendar

class QuickAddBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetQuickAddBinding? = null
    private val binding get() = _binding!!

    private var destination by mutableStateOf<DataScope?>(null)
    private var households by mutableStateOf<List<HouseholdEntity>>(emptyList())
    private var saveError by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var preferredHousehold: String? = null
    private val destinationName: String get() = destination?.householdId?.let { id ->
        households.firstOrNull { it.householdId == id }?.name ?: "Unavailable household"
    } ?: "Personal (private)"
    private val destinationReady: Boolean get() = destination?.let { target ->
        target.householdId == null || households.any { it.householdId == target.householdId }
    } == true

    private var selectedExpiryTimestamp: Long? = ExpiryEstimator.estimateExpiryTimestamp(null, "Dairy")
    private var expiryManuallySelected = false

    companion object {
        const val ARG_FULL_SCREEN = "full_screen"
        private const val ARG_PREFILLED_NAME = "prefilled_name"
        private const val ARG_PREFILLED_QTY = "prefilled_qty"
        private const val ARG_EDIT_ID = "edit_id"
        private const val ARG_EDIT_USER = "edit_user"
        private const val ARG_EDIT_HOUSEHOLD = "edit_household"
        private const val ARG_EDIT_PRICE = "edit_price"
        private const val ARG_EDIT_CATEGORY = "edit_category"
        private const val ARG_EDIT_EXPIRY = "edit_expiry"
        private const val ARG_EDIT_THRESHOLD = "edit_threshold"
        private const val ARG_EDIT_PURCHASED = "edit_purchased"
        private const val STATE_DESTINATION_USER = "destination_user"
        private const val STATE_DESTINATION_HOME = "destination_home"

        fun newInstance(name: String? = null, qty: Int = 1): QuickAddBottomSheetDialog {
            val fragment = QuickAddBottomSheetDialog()
            val args = Bundle().apply {
                putString(ARG_PREFILLED_NAME, name)
                putInt(ARG_PREFILLED_QTY, qty)
            }
            fragment.arguments = args
            return fragment
        }

        fun newEditInstance(item: GroceryItem): QuickAddBottomSheetDialog {
            val fragment = QuickAddBottomSheetDialog()
            val args = Bundle().apply {
                putInt(ARG_EDIT_ID, item.id)
                putString(ARG_EDIT_USER, item.userId)
                putString(ARG_EDIT_HOUSEHOLD, item.householdId)
                putString(ARG_PREFILLED_NAME, item.name)
                putInt(ARG_PREFILLED_QTY, item.quantity)
                putDouble(ARG_EDIT_PRICE, item.price)
                putString(ARG_EDIT_CATEGORY, item.categorySelection)
                putLong(ARG_EDIT_EXPIRY, item.expiryDate ?: 0L)
                putInt(ARG_EDIT_THRESHOLD, item.threshold)
                putLong(ARG_EDIT_PURCHASED, item.purchasedDate)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments?.getBoolean(ARG_FULL_SCREEN) == true) showsDialog = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetQuickAddBinding.inflate(inflater, container, false)
        // Keep the existing form binding and its listeners as the single source of form behavior.
        return ComposeView(requireContext()).apply {
            id = R.id.add_grocery_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupCategorySpinner()
        prefillData()
        setupDatePicker()
        setupListeners()
        setupExpiryEstimation()
        if (!isEditMode()) {
            updateEstimatedExpiryDate(force = true)
        }
        // These detached legacy fields remain the form model; restore their UI state explicitly.
        savedInstanceState?.let { saved ->
            saved.getSparseParcelableArray<android.os.Parcelable>("grocery_form_fields")?.let {
                binding.root.restoreHierarchyState(it)
            }
            selectedExpiryTimestamp = saved.getLong("grocery_form_expiry", selectedExpiryTimestamp ?: 0L).takeIf { it > 0L }
            expiryManuallySelected = saved.getBoolean("grocery_form_manual_expiry", expiryManuallySelected)
            binding.editExpiryDate.setText(DateUtils.formatDate(selectedExpiryTimestamp))
        }
        val manager = (requireActivity().application as SmartGroceryApp).sessionManager
        viewLifecycleOwner.lifecycleScope.launch {
            val session = manager.currentSession()
            val actor = session?.userId
            if (actor == null) { saveError = "Sign in before adding inventory."; return@launch }
            val originalActor = savedInstanceState?.getString(STATE_DESTINATION_USER)
            if (originalActor != null && originalActor != actor) {
                saveError = "Account changed. Reopen the inventory form before saving."; return@launch
            }
            preferredHousehold = session.activeHouseholdId ?: session.householdId
            households = manager.households().sortedBy { if (it.householdId == preferredHousehold) 0 else 1 }
            destination = DataScope(actor, when {
                originalActor != null -> savedInstanceState.getString(STATE_DESTINATION_HOME)
                isEditMode() -> arguments?.getString(ARG_EDIT_HOUSEHOLD)
                else -> null // New items stay private unless Household is explicitly chosen.
            })
        }
        childFragmentManager.setFragmentResultListener(IngredientScanBottomSheetDialog.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val count = result.getInt("added_count")
            if (count > 0) {
                Toast.makeText(requireContext(), "$count item(s) added to inventory", Toast.LENGTH_SHORT).show()
                dismiss() // Return to Inventory; its existing Room flow refreshes the cards.
            }
        }
        (view as ComposeView).setContent {
            val name by rememberBoundText(binding.editItemName)
            val quantity by rememberBoundText(binding.editQuantity)
            val price by rememberBoundText(binding.editPrice)
            val category by rememberBoundText(binding.spinnerCategory)
            val expiryDate by rememberBoundText(binding.editExpiryDate)
            val threshold by rememberBoundText(binding.editThreshold)
            var nameError by remember(name) { mutableStateOf<String?>(null) }
            val profile = remember { LocalAuthSession(requireContext()).currentProfile() }
            PantryPalTheme {
                Surface(color = if (isEditMode()) androidx.compose.ui.graphics.Color.Transparent else PantryColors.Cream) {
                    AddGroceryItemScreen(
                        destinationName = destinationName, destinationHousehold = destination?.householdId != null,
                        destinationReady = destinationReady, saving = saving, saveError = saveError,
                        householdOptions = households.map { HouseholdContextOption(it.householdId, it.name) },
                        onDestinationSelected = { household ->
                            if (!saving) {
                                if (!household) selectDestination(null)
                                else if (households.isEmpty()) saveError = "Create or join a household before sharing inventory."
                                else selectDestination(households.first().householdId)
                            }
                        },
                        onHouseholdSelected = { id -> if (!saving) selectDestination(id) },
                        name = name, quantity = quantity, price = price, category = category,
                        expiryDate = remember(expiryDate) {
                            DateUtils.formatDate(selectedExpiryTimestamp)
                        }, threshold = threshold,
                        categories = (0 until binding.spinnerCategory.adapter.count).map {
                            binding.spinnerCategory.adapter.getItem(it).toString()
                        },
                        onNameChange = { binding.editItemName.setText(it); nameError = null },
                        onQuantityChange = { binding.editQuantity.setText(it) },
                        onPriceChange = { binding.editPrice.setText(it) },
                        onCategorySelected = { value ->
                            if (!isEditMode() && threshold == com.xmum.smartgrocery.utils.GroceryCategories.defaultThreshold(category).toString()) {
                                binding.editThreshold.setText(com.xmum.smartgrocery.utils.GroceryCategories.defaultThreshold(value).toString())
                            }
                            binding.spinnerCategory.setText(value, false)
                            val position = (0 until binding.spinnerCategory.adapter.count).first {
                                binding.spinnerCategory.adapter.getItem(it).toString() == value
                            }
                            binding.spinnerCategory.onItemClickListener?.onItemClick(null, null, position, position.toLong())
                        },
                        onThresholdChange = { binding.editThreshold.setText(it) },
                        onExpiryDateClick = { binding.editExpiryDate.performClick() },
                        onSaveItem = { binding.buttonSave.performClick(); nameError = binding.editItemName.error?.toString() },
                        onBack = { binding.buttonCancel.performClick() },
                        initials = profile.displayName.split(' ').filter { it.isNotBlank() }.take(2)
                            .joinToString("") { it.first().uppercaseChar().toString() }.ifBlank { null },
                        nameError = nameError, isEditMode = isEditMode(),
                        onScanIngredients = {
                            val target = destination
                            if (!saving && destinationReady && target != null && childFragmentManager.findFragmentByTag(IngredientScanBottomSheetDialog.TAG) == null) {
                                IngredientScanBottomSheetDialog.newInstance(target, destinationName)
                                    .show(childFragmentManager, IngredientScanBottomSheetDialog.TAG)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (isEditMode()) {
            dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun setupCategorySpinner() {
        val categories = com.xmum.smartgrocery.utils.GroceryCategories.all
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        binding.spinnerCategory.setAdapter(adapter)
        binding.spinnerCategory.setText(categories[0], false) // select first by default
        binding.spinnerCategory.setOnItemClickListener { _, _, _, _ ->
            updateEstimatedExpiryDate(force = false)
        }
    }

    private fun setupDatePicker() {
        binding.editExpiryDate.setText(DateUtils.formatDate(selectedExpiryTimestamp))
        binding.editExpiryDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = selectedExpiryTimestamp ?: System.currentTimeMillis()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            DatePickerDialog(requireContext(), { _, selYear, selMonth, selDay ->
                val selectedCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, selYear)
                    set(Calendar.MONTH, selMonth)
                    set(Calendar.DAY_OF_MONTH, selDay)
                }
                selectedExpiryTimestamp = selectedCal.timeInMillis
                expiryManuallySelected = true
                binding.editExpiryDate.setText(DateUtils.formatDate(selectedExpiryTimestamp))
            }, year, month, day).apply {
                setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Not Set") { _, _ ->
                    selectedExpiryTimestamp = null
                    expiryManuallySelected = true
                    binding.editExpiryDate.setText("Not Set")
                }
            }.show()
        }
    }

    private fun setupExpiryEstimation() {
        binding.editItemName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateEstimatedExpiryDate(force = false)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateEstimatedExpiryDate(force: Boolean) {
        if (!force && expiryManuallySelected) {
            return
        }

        selectedExpiryTimestamp = ExpiryEstimator.estimateExpiryTimestamp(
            itemName = binding.editItemName.text?.toString(),
            category = binding.spinnerCategory.text?.toString()
        )
        binding.editExpiryDate.setText(DateUtils.formatDate(selectedExpiryTimestamp))
    }

    private fun prefillData() {
        if (isEditMode()) {
            binding.textSheetTitle.text = "Edit Item"
            binding.buttonSave.text = "Update"
            binding.buttonSave.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_edit_24, 0, 0, 0)
            binding.editPrice.setText(String.format("%.2f", arguments?.getDouble(ARG_EDIT_PRICE) ?: 0.0))
            binding.spinnerCategory.setText(arguments?.getString(ARG_EDIT_CATEGORY) ?: "Other", false)
            binding.editThreshold.setText((arguments?.getInt(ARG_EDIT_THRESHOLD) ?: 2).toString())
            selectedExpiryTimestamp = arguments?.getLong(ARG_EDIT_EXPIRY)?.takeIf { it > 0L }
            expiryManuallySelected = true
        } else {
            binding.textSheetTitle.text = getString(R.string.add_item)
            binding.buttonSave.text = "Save"
            binding.buttonSave.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle_24, 0, 0, 0)
        }

        arguments?.getString(ARG_PREFILLED_NAME)?.let { name ->
            binding.editItemName.setText(name)
        }
        arguments?.getInt(ARG_PREFILLED_QTY)?.let { qty ->
            binding.editQuantity.setText(qty.toString())
        }
    }

    private fun setupListeners() {
        binding.buttonCancel.setOnClickListener {
            if (!saving) dismiss()
        }

        binding.buttonSave.setOnClickListener {
            saveItem()
        }
    }

    private fun saveItem() {
        if (saving) return
        val target = destination
        if (target == null || !destinationReady) { saveError = "Choose an available save destination first."; return }
        val name = binding.editItemName.text?.toString()?.trim()
        val qtyStr = binding.editQuantity.text?.toString()?.trim()
        val priceStr = binding.editPrice.text?.toString()?.trim()
        val category = binding.spinnerCategory.text?.toString() ?: "Other"
        val thresholdStr = binding.editThreshold.text?.toString()?.trim()

        if (name.isNullOrBlank()) {
            binding.editItemName.error = "Name is required"
            return
        }

        val qty = qtyStr?.toIntOrNull() ?: 1
        val price = priceStr?.toDoubleOrNull() ?: 0.0
        val threshold = thresholdStr?.toIntOrNull() ?: 2

        val item = if (isEditMode()) {
            GroceryItem(
                id = arguments?.getInt(ARG_EDIT_ID) ?: 0,
                userId = arguments?.getString(ARG_EDIT_USER).orEmpty(),
                householdId = arguments?.getString(ARG_EDIT_HOUSEHOLD),
                name = name,
                quantity = qty,
                price = price,
                category = category,
                expiryDate = selectedExpiryTimestamp,
                threshold = threshold,
                purchasedDate = arguments?.getLong(ARG_EDIT_PURCHASED) ?: System.currentTimeMillis()
            )
        } else {
            GroceryItem(
                name = name,
                quantity = qty,
                price = price,
                category = category,
                expiryDate = selectedExpiryTimestamp,
                threshold = threshold
            )
        }
        saving = true
        saveError = null
        isCancelable = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val app = requireActivity().application as SmartGroceryApp
                app.sessionManager.withInventoryDestination(target) {
                    if (isEditMode()) {
                        checkNotNull(app.groceryRepository.getItemById(item.id)) { "This item is no longer available in this space." }
                        app.groceryRepository.update(item)
                    } else app.groceryRepository.insert(item)
                }
                Toast.makeText(requireContext(), "$name saved to $destinationName", Toast.LENGTH_SHORT).show()
                dismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { saveError = error.message ?: "Unable to save this item. Your entries have been kept." }
            finally { saving = false; isCancelable = true }
        }
    }

    private fun selectDestination(householdId: String?) {
        val actor = destination?.userId ?: return
        if (householdId != null && households.none { it.householdId == householdId }) {
            saveError = "This household is unavailable."; return
        }
        destination = DataScope(actor, householdId)
        saveError = null
    }

    private fun isEditMode(): Boolean {
        return arguments?.containsKey(ARG_EDIT_ID) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        destination?.let {
            outState.putString(STATE_DESTINATION_USER, it.userId)
            outState.putString(STATE_DESTINATION_HOME, it.householdId)
        }
        _binding?.let { form ->
            val fields = android.util.SparseArray<android.os.Parcelable>()
            form.root.saveHierarchyState(fields)
            outState.putSparseParcelableArray("grocery_form_fields", fields)
            outState.putLong("grocery_form_expiry", selectedExpiryTimestamp ?: 0L)
            outState.putBoolean("grocery_form_manual_expiry", expiryManuallySelected)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

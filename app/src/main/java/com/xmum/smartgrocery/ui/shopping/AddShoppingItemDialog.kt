package com.xmum.smartgrocery.ui.shopping

import com.xmum.smartgrocery.ui.common.configureFormScrolling
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.databinding.BottomSheetShoppingItemBinding
import com.xmum.smartgrocery.ui.pantrypal.AddShoppingItemSheet
import com.xmum.smartgrocery.ui.pantrypal.PantryPalTheme
import com.xmum.smartgrocery.ui.pantrypal.rememberBoundText
import com.xmum.smartgrocery.ui.pantrypal.restoreLegacyUiState
import com.xmum.smartgrocery.ui.pantrypal.saveLegacyUiState

class AddShoppingItemDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetShoppingItemBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShoppingListViewModel by viewModels()

    companion object {
        private const val ARG_PREFILLED_NAME = "prefilled_name"
        private const val ARG_PREFILLED_QTY = "prefilled_qty"

        fun newInstance(name: String? = null, qty: Int = 1): AddShoppingItemDialog {
            return AddShoppingItemDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PREFILLED_NAME, name)
                    putInt(ARG_PREFILLED_QTY, qty)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetShoppingItemBinding.inflate(inflater, container, false)
        return ComposeView(requireContext()).apply {
            id = R.id.add_shopping_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDropdowns()
        prefillData()
        setupListeners()
        restoreLegacyUiState(binding.root, savedInstanceState)
        (view as ComposeView).setContent {
            val name by rememberBoundText(binding.editShoppingItemName)
            val quantity by rememberBoundText(binding.editShoppingQuantity)
            val price by rememberBoundText(binding.editShoppingPrice)
            val category by rememberBoundText(binding.spinnerShoppingCategory)
            val assignee by rememberBoundText(binding.spinnerShoppingAssignee)
            var nameError by remember { mutableStateOf(binding.editShoppingItemName.error?.toString()) }
            PantryPalTheme {
                AddShoppingItemSheet(
                    name = name, quantity = quantity, price = price, category = category, assignee = assignee,
                    categories = (0 until binding.spinnerShoppingCategory.adapter.count).map {
                        binding.spinnerShoppingCategory.adapter.getItem(it).toString()
                    },
                    assignees = (0 until binding.spinnerShoppingAssignee.adapter.count).map {
                        binding.spinnerShoppingAssignee.adapter.getItem(it).toString()
                    },
                    nameError = nameError,
                    onNameChange = {
                        binding.editShoppingItemName.setText(it)
                        binding.editShoppingItemName.error = null
                        nameError = null
                    },
                    onQuantityChange = { binding.editShoppingQuantity.setText(it) },
                    onPriceChange = { binding.editShoppingPrice.setText(it) },
                    onCategorySelected = { binding.spinnerShoppingCategory.setText(it, false) },
                    onAssigneeSelected = { binding.spinnerShoppingAssignee.setText(it, false) },
                    onSave = {
                        binding.buttonSaveShopping.performClick()
                        _binding?.let { nameError = it.editShoppingItemName.error?.toString() }
                    },
                    onCancel = { binding.buttonCancelShopping.performClick() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            BottomSheetBehavior.from(sheet).configureFormScrolling()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { saveLegacyUiState(it.root, outState) }
    }

    private fun setupDropdowns() {
        val categories = com.xmum.smartgrocery.utils.GroceryCategories.all
        val assignees = arrayOf("Me", "Household")

        binding.spinnerShoppingCategory.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categories)
        )
        binding.spinnerShoppingAssignee.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, assignees)
        )
        binding.spinnerShoppingCategory.setText(categories[0], false)
        binding.spinnerShoppingAssignee.setText(assignees[1], false)
    }

    private fun prefillData() {
        arguments?.getString(ARG_PREFILLED_NAME)?.let { name ->
            binding.editShoppingItemName.setText(name)
        }
        val quantity = arguments?.getInt(ARG_PREFILLED_QTY) ?: 1
        binding.editShoppingQuantity.setText(quantity.coerceAtLeast(1).toString())
    }

    private fun setupListeners() {
        binding.buttonCancelShopping.setOnClickListener { dismiss() }
        binding.buttonSaveShopping.setOnClickListener { saveShoppingItem() }
    }

    private fun saveShoppingItem() {
        if (!binding.buttonSaveShopping.isEnabled) return
        val name = binding.editShoppingItemName.text?.toString()?.trim()
        if (name.isNullOrBlank()) {
            binding.editShoppingItemName.error = "Name is required"
            return
        }

        val quantity = (binding.editShoppingQuantity.text?.toString()?.trim()?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val totalPrice = binding.editShoppingPrice.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        // The form accepts a line total; existing storage and cart calculations use unit prices.
        val price = totalPrice / quantity
        val category = binding.spinnerShoppingCategory.text?.toString()?.ifBlank { "Other" } ?: "Other"
        val assignee = binding.spinnerShoppingAssignee.text?.toString()?.ifBlank { "Household" } ?: "Household"

        binding.buttonSaveShopping.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val error = viewModel.addManualItem(name, category, quantity, price, assignee)
            if (_binding == null) return@launch
            binding.buttonSaveShopping.isEnabled = true
            Toast.makeText(requireContext(), error ?: "$name added to shopping list", Toast.LENGTH_LONG).show()
            if (error == null) dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

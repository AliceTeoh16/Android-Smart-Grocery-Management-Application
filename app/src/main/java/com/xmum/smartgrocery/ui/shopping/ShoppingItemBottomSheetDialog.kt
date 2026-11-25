package com.xmum.smartgrocery.ui.shopping

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.databinding.BottomSheetShoppingItemBinding
import kotlinx.coroutines.launch

class ShoppingItemBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetShoppingItemBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_PREFILLED_NAME = "prefilled_name"
        private const val ARG_PREFILLED_QTY = "prefilled_qty"

        fun newInstance(name: String? = null, qty: Int = 1): ShoppingItemBottomSheetDialog {
            return ShoppingItemBottomSheetDialog().apply {
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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDropdowns()
        prefillData()
        setupListeners()
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

        val quantity = binding.editShoppingQuantity.text?.toString()?.trim()?.toIntOrNull() ?: 1
        val price = binding.editShoppingPrice.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        val category = binding.spinnerShoppingCategory.text?.toString()?.ifBlank { "Other" } ?: "Other"
        val assignee = binding.spinnerShoppingAssignee.text?.toString()?.ifBlank { "Household" } ?: "Household"
        val repository = (requireActivity().application as SmartGroceryApp).shoppingListRepository

        binding.buttonSaveShopping.isEnabled = false
        lifecycleScope.launch {
            try { repository.addManualItem(
                name,
                category,
                quantity,
                price,
                assignee
            )
            Toast.makeText(requireContext(), "$name added to shopping list", Toast.LENGTH_SHORT).show()
            dismiss()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (_binding != null) {
                    binding.buttonSaveShopping.isEnabled = true
                    Toast.makeText(requireContext(), error.message ?: "Unable to save shopping item.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

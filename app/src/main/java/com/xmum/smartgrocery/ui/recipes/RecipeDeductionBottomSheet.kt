package com.xmum.smartgrocery.ui.recipes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.dao.RecipeDeductUpdate
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.Recipe
import com.xmum.smartgrocery.data.local.entity.RecipeIngredient
import com.xmum.smartgrocery.databinding.BottomSheetRecipeDeductionBinding
import com.xmum.smartgrocery.databinding.ItemRecipeDeductBinding
import kotlinx.coroutines.launch
import java.util.ArrayList
import kotlin.math.ceil

class RecipeDeductionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRecipeDeductionBinding? = null
    private val binding get() = _binding!!

    private val groceryRepository by lazy {
        (requireActivity().application as SmartGroceryApp).groceryRepository
    }
    private val deductionLines = mutableListOf<DeductionLine>()
    private val gson = Gson()

    companion object {
        private const val ARG_RECIPE = "recipe"
        private const val ARG_MISSING_INGREDIENTS = "missing_ingredients"

        fun newInstance(recipe: Recipe, missingIngredients: List<String>): RecipeDeductionBottomSheet {
            return RecipeDeductionBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_RECIPE, Gson().toJson(recipe))
                    putStringArrayList(ARG_MISSING_INGREDIENTS, ArrayList(missingIngredients))
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRecipeDeductionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recipeJson = arguments?.getString(ARG_RECIPE) ?: return
        val missingIngredients = arguments?.getStringArrayList(ARG_MISSING_INGREDIENTS) ?: emptyList<String>()
        val recipe = gson.fromJson(recipeJson, Recipe::class.java)

        binding.textDeductionTitle.text = "Confirm ${recipe.name}"
        binding.buttonCancelDeduction.setOnClickListener { dismiss() }
        binding.buttonConfirmDeduction.setOnClickListener { confirmDeduction() }
        loadDeductionLines(recipe, missingIngredients)
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun loadDeductionLines(recipe: Recipe, missingIngredients: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            deductionLines.clear()
            recipe.ingredients
                .filterNot { ingredient -> isMissing(ingredient, missingIngredients) }
                .forEach { ingredient ->
                    val item = groceryRepository.findBestMatchingItem(ingredient.name)
                    if (item != null && item.quantity > 0) {
                        deductionLines.add(
                            DeductionLine(
                                groceryItem = item,
                                ingredient = ingredient,
                                quantityUsed = defaultDeductionAmount(ingredient).coerceAtMost(item.quantity)
                            )
                        )
                    }
                }
            renderDeductionLines()
        }
    }

    private fun renderDeductionLines() {
        binding.layoutDeductionItems.removeAllViews()
        binding.buttonConfirmDeduction.isEnabled = deductionLines.any { it.quantityUsed > 0 }

        if (deductionLines.isEmpty()) {
            val emptyText = android.widget.TextView(requireContext()).apply {
                text = "No owned required ingredients are available to deduct."
                setTextColor(resources.getColor(com.xmum.smartgrocery.R.color.text_secondary, null))
                textSize = 14f
            }
            binding.layoutDeductionItems.addView(emptyText)
            return
        }

        deductionLines.forEachIndexed { index, line ->
            val row = ItemRecipeDeductBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.layoutDeductionItems,
                false
            )
            bindDeductionRow(row, index)
            binding.layoutDeductionItems.addView(row.root)
        }
    }

    private fun bindDeductionRow(row: ItemRecipeDeductBinding, index: Int) {
        val line = deductionLines[index]
        row.textDeductItemName.text = line.groceryItem.name
        row.textDeductItemMeta.text = "Recipe: ${line.ingredient.quantityRequired} ${line.ingredient.unit} - In stock: ${line.groceryItem.quantity}"
        row.textDeductQuantity.text = line.quantityUsed.toString()

        row.buttonDeductDecrement.setOnClickListener {
            line.quantityUsed = (line.quantityUsed - 1).coerceAtLeast(0)
            renderDeductionLines()
        }
        row.buttonDeductIncrement.setOnClickListener {
            line.quantityUsed = (line.quantityUsed + 1).coerceAtMost(line.groceryItem.quantity)
            renderDeductionLines()
        }
    }

    private fun confirmDeduction() {
        viewLifecycleOwner.lifecycleScope.launch {
            val updates = deductionLines
                .filter { it.quantityUsed > 0 }
                .map { RecipeDeductUpdate(it.groceryItem.id, it.quantityUsed) }

            if (updates.isEmpty()) {
                Toast.makeText(requireContext(), "No quantities selected.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            groceryRepository.deductRecipeItems(updates)
            Toast.makeText(requireContext(), "Inventory updated for ${updates.size} item(s).", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun defaultDeductionAmount(ingredient: RecipeIngredient): Int {
        return when (ingredient.unit.lowercase()) {
            "pc", "pcs", "piece", "pieces", "slice", "slices", "stalk", "stalks", "unit", "units" ->
                ceil(ingredient.quantityRequired).toInt().coerceAtLeast(1)
            else -> 1
        }
    }

    private fun isMissing(ingredient: RecipeIngredient, missingIngredients: List<String>): Boolean {
        return missingIngredients.any {
            it.contains(ingredient.name, ignoreCase = true) ||
                ingredient.name.contains(it, ignoreCase = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class DeductionLine(
        val groceryItem: GroceryItem,
        val ingredient: RecipeIngredient,
        var quantityUsed: Int
    )
}

package com.xmum.smartgrocery.ui.recipes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.xmum.smartgrocery.data.local.entity.Recipe
import com.xmum.smartgrocery.databinding.BottomSheetRecipeDetailBinding
import com.xmum.smartgrocery.databinding.ItemRecipeIngredientCheckBinding
import com.xmum.smartgrocery.databinding.ItemRecipeInstructionStepBinding
import java.util.ArrayList

class RecipeDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRecipeDetailBinding? = null
    private val binding get() = _binding!!

    private val gson = Gson()

    companion object {
        private const val ARG_RECIPE = "recipe"
        private const val ARG_MISSING_INGREDIENTS = "missing_ingredients"

        fun newInstance(recipe: Recipe, missingIngredients: List<String>): RecipeDetailBottomSheet {
            val fragment = RecipeDetailBottomSheet()
            val args = Bundle().apply {
                putString(ARG_RECIPE, Gson().toJson(recipe))
                putStringArrayList(ARG_MISSING_INGREDIENTS, ArrayList(missingIngredients))
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRecipeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recipeJson = arguments?.getString(ARG_RECIPE) ?: return
        val missingIngredients = arguments?.getStringArrayList(ARG_MISSING_INGREDIENTS) ?: emptyList<String>()
        val recipe = gson.fromJson(recipeJson, Recipe::class.java)

        setupRecipeDetails(recipe, missingIngredients)
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun setupRecipeDetails(recipe: Recipe, missingIngredients: List<String>) {
        binding.textRecipeDetailName.text = recipe.name

        val localImage = RecipeImageResolver.drawableForRecipe(recipe.id)
        if (localImage != null) {
            binding.imageRecipeDetail.setImageResource(localImage)
        } else {
            Glide.with(this)
                .load(recipe.imageUrl)
                .centerCrop()
                .placeholder(com.xmum.smartgrocery.R.drawable.pantry_recipe_photo_placeholder)
                .into(binding.imageRecipeDetail)
        }

        // Setup ingredients list checklist
        binding.layoutIngredientsList.removeAllViews()
        recipe.ingredients.forEach { ingredient ->
            val ingredientBinding = ItemRecipeIngredientCheckBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.layoutIngredientsList,
                false
            )
            
            val isMissing = missingIngredients.any { it.contains(ingredient.name, ignoreCase = true) || ingredient.name.contains(it, ignoreCase = true) }
            
            ingredientBinding.textIngredientDetail.text = "${ingredient.quantityRequired} ${ingredient.unit} ${ingredient.name}"
            
            if (isMissing) {
                ingredientBinding.imageCheckStatus.setImageResource(android.R.drawable.ic_delete)
                ingredientBinding.imageCheckStatus.setColorFilter(resources.getColor(com.xmum.smartgrocery.R.color.alert_red, null))
                ingredientBinding.textStatusLabel.text = "Missing"
                ingredientBinding.textStatusLabel.setTextColor(resources.getColor(com.xmum.smartgrocery.R.color.alert_red, null))
            } else {
                ingredientBinding.imageCheckStatus.setImageResource(android.R.drawable.checkbox_on_background)
                ingredientBinding.imageCheckStatus.setColorFilter(resources.getColor(com.xmum.smartgrocery.R.color.emerald, null))
                ingredientBinding.textStatusLabel.text = "Owned"
                ingredientBinding.textStatusLabel.setTextColor(resources.getColor(com.xmum.smartgrocery.R.color.emerald, null))
            }
            
            binding.layoutIngredientsList.addView(ingredientBinding.root)
        }

        // Setup instructions list steps
        binding.layoutInstructionsList.removeAllViews()
        recipe.instructions.forEachIndexed { index, step ->
            val stepBinding = ItemRecipeInstructionStepBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.layoutInstructionsList,
                false
            )
            stepBinding.textStepNumber.text = (index + 1).toString()
            stepBinding.textStepDesc.text = step
            binding.layoutInstructionsList.addView(stepBinding.root)
        }

        if (recipe.videoUrl.isBlank()) {
            binding.buttonWatchVideo.visibility = View.GONE
        } else {
            binding.buttonWatchVideo.visibility = View.VISIBLE
            binding.buttonWatchVideo.setOnClickListener {
                val videoIntent = Intent(Intent.ACTION_VIEW, Uri.parse(recipe.videoUrl))
                try {
                    startActivity(Intent.createChooser(videoIntent, "Open video tutorial"))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "No browser or video application found to open this video.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.buttonCookedDeduct.setOnClickListener {
            RecipeDeductionBottomSheet
                .newInstance(recipe, missingIngredients)
                .show(parentFragmentManager, "RecipeDeductionBottomSheet")
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

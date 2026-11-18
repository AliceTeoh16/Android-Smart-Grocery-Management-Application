package com.xmum.smartgrocery.data.network.dto

import com.google.gson.annotations.SerializedName

data class RecipeResponse(
    @SerializedName("dish_name")
    val dishName: String? = null,
    val servings: Int? = null,
    @SerializedName("missing_checklist")
    val calculatorMissingChecklist: List<CalculatorMissingItemDto> = emptyList(),
    @SerializedName("inventory_deducted")
    val inventoryDeducted: List<InventoryDeductedDto> = emptyList(),
    val recipes: List<AiRecipeDto> = emptyList(),
    @SerializedName("available_recipes")
    val availableRecipes: List<AiRecipeDto> = emptyList(),
    val ingredients: List<ChecklistIngredientDto>? = null,
    val steps: List<String>? = null
)

data class ChecklistIngredientDto(
    val name: String = "",
    val amount: String = "",
    @SerializedName("is_in_inventory") val isInInventory: Boolean = false
)

data class CalculatorMissingItemDto(
    val name: String = "",
    @SerializedName("missing_qty")
    val missingQty: String = "",
    val category: String = "Other"
)

data class InventoryDeductedDto(
    val name: String = "",
    @SerializedName("qty_used")
    val qtyUsed: String = ""
)

data class AiErrorResponse(
    val error: String? = null,
    val detail: String? = null,
    val code: String? = null
)

data class AiRecipeDto(
    val id: Int? = null,
    @SerializedName("recipe_id")
    val recipeId: String? = null,
    @SerializedName("food_name")
    val foodName: String? = null,
    @SerializedName("image_search_keyword")
    val imageSearchKeyword: String? = null,
    val title: String? = null,
    val name: String? = null,
    val description: String? = null,
    @SerializedName("ingredients_needed")
    val ingredientsNeeded: List<AiRecipeIngredientDto> = emptyList(),
    val ingredients: List<AiRecipeIngredientDto> = emptyList(),
    @SerializedName(value = "ingredients_used", alternate = ["ingredientsUsed"])
    val ingredientsUsed: List<String> = emptyList(),
    @SerializedName(value = "missing_ingredients", alternate = ["missingIngredients"])
    val missingIngredients: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    @SerializedName(value = "image_url", alternate = ["imageUrl"])
    val imageUrl: String = "",
    @SerializedName(value = "video_url", alternate = ["videoUrl"])
    val videoUrl: String = "",
    @SerializedName(value = "prep_time_minutes", alternate = ["prepTimeMinutes"])
    val prepTimeMinutes: Int = 20
)

data class AiRecipeIngredientDto(
    @SerializedName("item_id")
    val itemId: Int? = null,
    val name: String,
    @SerializedName(value = "amount_deduct", alternate = ["quantityRequired"])
    val amountDeduct: Double = 1.0,
    val unit: String = "pcs",
    @SerializedName("is_main_ingredient")
    val isMainIngredient: Boolean = true,
    val optional: Boolean = false
)

package com.xmum.smartgrocery.data.network.dto

import com.google.gson.annotations.SerializedName
import com.xmum.smartgrocery.data.network.AiSystemPrompts

data class RecipeRequest(
    @SerializedName("system_instruction")
    val systemInstruction: String = AiSystemPrompts.RECIPE_GENERATOR,
    @SerializedName("available_ingredients")
    val availableIngredients: List<RecipeIngredientDto>,
    @SerializedName("pantry_staples_included")
    val pantryStaplesIncluded: Boolean,
    @SerializedName("pantry_staples")
    val pantryStaples: List<String>,
    @SerializedName("prioritize_expiring_soon")
    val prioritizeExpiringSoon: Boolean,
    @SerializedName("min_match_percent")
    val minMatchPercent: Int,
    @SerializedName("max_recipes")
    val maxRecipes: Int = 5,
    @SerializedName("user_prompt")
    val userPrompt: String? = null
)

data class RecipeIngredientDto(
    @SerializedName("item_id")
    val itemId: Int,
    val name: String,
    val quantity: Double,
    val unit: String = "pcs",
    @SerializedName("is_expiring_soon")
    val isExpiringSoon: Boolean,
    @SerializedName("is_staple")
    val isStaple: Boolean = false
)

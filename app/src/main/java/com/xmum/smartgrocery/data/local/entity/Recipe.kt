package com.xmum.smartgrocery.data.local.entity

data class RecipeIngredient(
    val name: String,
    val quantityRequired: Double,
    val unit: String,
    val optional: Boolean = false
)

data class Recipe(
    val id: Int,
    val name: String,
    val ingredients: List<RecipeIngredient>,
    val instructions: List<String>,
    val imageUrl: String,
    val videoUrl: String,
    val prepTimeMinutes: Int = 20
)

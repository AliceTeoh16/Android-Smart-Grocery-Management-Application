package com.xmum.smartgrocery.data.repository

import com.google.gson.Gson
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.local.entity.Recipe
import com.xmum.smartgrocery.data.local.entity.RecipeIngredient
import com.xmum.smartgrocery.data.network.AiApiService
import com.xmum.smartgrocery.data.network.dto.AiErrorResponse
import com.xmum.smartgrocery.data.network.dto.RecipeIngredientDto
import com.xmum.smartgrocery.data.network.dto.RecipeRequest
import com.xmum.smartgrocery.utils.DateUtils
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class RecipeRepository(
    private val aiApiService: AiApiService? = null
) {

    private val gson = Gson()

    data class GroceryChecklistResult(
        val dishName: String,
        val servings: Int,
        val missingChecklist: List<MissingChecklistItem>,
        val alreadyHave: List<String>,
        val inventoryDeducted: List<DeductedInventoryItem> = emptyList(),
        val ingredients: List<ChecklistIngredient> = emptyList(),
        val steps: List<String> = emptyList()
    )

    data class ChecklistIngredient(val name: String, val amount: String, val isInInventory: Boolean)

    data class MissingChecklistItem(
        val item: String,
        val neededQuantity: String,
        val category: String
    )

    data class DeductedInventoryItem(
        val item: String,
        val usedQuantity: String
    )

    // Preloaded preset recipes matching typical home grocery purchases
    val presetRecipes: List<Recipe> = listOf(
        Recipe(
            id = 1,
            name = "Tomato Scrambled Eggs",
            ingredients = listOf(
                RecipeIngredient("Egg", 3.0, "pcs"),
                RecipeIngredient("Tomato", 2.0, "pcs"),
                RecipeIngredient("Scallion", 1.0, "stalk", optional = true)
            ),
            instructions = listOf(
                "Whisk the eggs in a bowl with a pinch of salt.",
                "Cut tomatoes into small wedges.",
                "Heat oil in a pan, pour in eggs and scramble gently until 80% cooked. Remove and set aside.",
                "In the same pan, stir-fry tomatoes until soft and juicy.",
                "Return eggs back to the pan, toss with chopped scallions, season with light soy sauce, and serve hot."
            ),
            imageUrl = "https://images.unsplash.com/photo-1598214813441-fd04166ecfc6?w=600&auto=format&fit=crop",
            videoUrl = "https://www.youtube.com/watch?v=1F_4-tGisw4",
            prepTimeMinutes = 15
        ),
        Recipe(
            id = 2,
            name = "Classic Avocado Toast",
            ingredients = listOf(
                RecipeIngredient("Avocado", 1.0, "pc"),
                RecipeIngredient("Bread", 2.0, "slices"),
                RecipeIngredient("Egg", 1.0, "pc")
            ),
            instructions = listOf(
                "Toast the bread slices until golden brown and crispy.",
                "Mash the avocado in a bowl with lemon juice, salt, and pepper.",
                "Fry or poach the egg to your liking.",
                "Spread the mashed avocado evenly over the toasted bread.",
                "Top with the cooked egg and a sprinkle of chili flakes if desired."
            ),
            imageUrl = "https://images.unsplash.com/photo-1719520670204-dbe1903a789f?w=600&auto=format&fit=crop",
            videoUrl = "https://www.youtube.com/watch?v=F5vVfL014a4",
            prepTimeMinutes = 10
        ),
        Recipe(
            id = 3,
            name = "Chicken Creamy Pasta",
            ingredients = listOf(
                RecipeIngredient("Chicken", 200.0, "g"),
                RecipeIngredient("Pasta", 150.0, "g"),
                RecipeIngredient("Milk", 100.0, "ml"),
                RecipeIngredient("Garlic", 2.0, "cloves", optional = true)
            ),
            instructions = listOf(
                "Boil pasta in salted water according to instructions. Drain and set aside.",
                "Sauté minced garlic in olive oil, then add diced chicken breast until cooked.",
                "Pour in milk (or heavy cream if available) and simmer until slightly thickened.",
                "Toss in the cooked pasta and mix well with salt, pepper, and cheese.",
                "Garnish with parsley and enjoy creamy home-cooked pasta."
            ),
            imageUrl = "https://images.unsplash.com/photo-1645112411341-6c4fd023714a?w=600&auto=format&fit=crop",
            videoUrl = "https://www.youtube.com/watch?v=LPE8Ctk9VwQ",
            prepTimeMinutes = 25
        ),
        Recipe(
            id = 4,
            name = "Fluffy Banana Pancakes",
            ingredients = listOf(
                RecipeIngredient("Banana", 1.0, "pc"),
                RecipeIngredient("Egg", 2.0, "pcs"),
                RecipeIngredient("Milk", 50.0, "ml")
            ),
            instructions = listOf(
                "Mash the banana in a bowl until smooth.",
                "Whisk in the eggs and milk until fully combined.",
                "Heat a non-stick pan over medium-low heat with a little butter or oil.",
                "Pour small circles of batter into the pan. Cook until bubbles form, then flip.",
                "Stack them high and serve with syrup, honey, or fresh berries."
            ),
            imageUrl = "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600&auto=format&fit=crop",
            videoUrl = "https://www.youtube.com/watch?v=842eDlyK-Fk",
            prepTimeMinutes = 18
        ),
        Recipe(
            id = 5,
            name = "French Toast",
            ingredients = listOf(
                RecipeIngredient("Bread", 3.0, "slices"),
                RecipeIngredient("Milk", 60.0, "ml"),
                RecipeIngredient("Egg", 2.0, "pcs")
            ),
            instructions = listOf(
                "Whisk eggs, milk, and a pinch of cinnamon together in a shallow dish.",
                "Dip each bread slice into the mixture, coating both sides well.",
                "Melt butter in a skillet over medium heat.",
                "Fry bread slices until golden brown on both sides (2-3 minutes per side).",
                "Serve warm with a drizzle of honey, maple syrup, or a dust of powdered sugar."
            ),
            imageUrl = "https://images.unsplash.com/photo-1484723091739-30a097e8f929?w=600&auto=format&fit=crop",
            videoUrl = "https://www.youtube.com/watch?v=2Tz8X-sR7Dk",
            prepTimeMinutes = 12
        )
    )

    suspend fun generateChecklistAssistantResult(
        inventory: List<GroceryItem>,
        userPrompt: String,
        expiryWarningDays: Int = 3
    ): GroceryChecklistResult {
        val sortedInventory = inventory
            .filter { it.quantity > 0 && com.xmum.smartgrocery.utils.GroceryCategories.isFood(it.category) && !DateUtils.isExpired(it.expiryDate) }
            .sortedWith(
                compareByDescending<GroceryItem> { DateUtils.isExpiringWithin(it.expiryDate, expiryWarningDays) }
                    .thenBy { it.expiryDate ?: Long.MAX_VALUE }
            )
        val ingredientDtos = sortedInventory.map {
            RecipeIngredientDto(
                itemId = it.id,
                name = it.name,
                quantity = it.quantity.toDouble(),
                unit = "pcs",
                isExpiringSoon = DateUtils.isExpiringWithin(it.expiryDate, expiryWarningDays)
            )
        }
        val request = RecipeRequest(
            availableIngredients = ingredientDtos,
            pantryStaplesIncluded = false,
            pantryStaples = emptyList(),
            prioritizeExpiringSoon = true,
            minMatchPercent = 0,
            maxRecipes = 1,
            userPrompt = userPrompt
        )

        val service = aiApiService
            ?: throw IllegalStateException("AI checklist service is not configured. Check AI_BASE_URL.")
        return try {
            val response = service.generateRecipes(request)
            val calculatorMissingItems = response.calculatorMissingChecklist
            if (response.dishName.isNullOrBlank()) {
                throw IllegalStateException("Gemini response missing dish_name.")
            }
            if (response.servings == null || response.servings <= 0) {
                throw IllegalStateException("Gemini response missing valid servings.")
            }
            val ingredients = response.ingredients.orEmpty()
            val steps = response.steps.orEmpty()
            // Old checklists remain readable; a new partial recipe must never be displayed as complete.
            if (ingredients.isNotEmpty() || steps.isNotEmpty()) {
                check(ingredients.size in 1..40 && steps.size in 1..24 &&
                    ingredients.all { it.name.isNotBlank() && it.amount.isNotBlank() } &&
                    steps.all { it.isNotBlank() && it.length <= 1600 }) {
                    "AI returned incomplete cooking instructions. Generate the checklist again."
                }
            }
            GroceryChecklistResult(
                dishName = response.dishName,
                servings = response.servings,
                missingChecklist = calculatorMissingItems.map {
                        MissingChecklistItem(
                            item = it.name,
                            neededQuantity = it.missingQty,
                            category = it.category
                        )
                },
                alreadyHave = response.inventoryDeducted.map { "${it.name} ${it.qtyUsed}" },
                inventoryDeducted = response.inventoryDeducted.map {
                    DeductedInventoryItem(
                        item = it.name,
                        usedQuantity = it.qtyUsed
                    )
                },
                ingredients = ingredients.map { ChecklistIngredient(it.name, it.amount, it.isInInventory) },
                steps = steps
            )
        } catch (error: HttpException) {
            throw IOException(error.readAiErrorMessage(), error)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw error
        }
    }

    suspend fun generateAiRecipes(
        inventory: List<GroceryItem>,
        prioritizeExpiringSoon: Boolean,
        pantryStaples: Set<String> = emptySet(),
        userPrompt: String? = null,
        minMatchPercent: Int = 80,
        expiryWarningDays: Int = 3
    ): List<Recipe> {
        val sortedInventory = inventory
            .filter { it.quantity > 0 && com.xmum.smartgrocery.utils.GroceryCategories.isFood(it.category) && !DateUtils.isExpired(it.expiryDate) }
            .sortedWith(
            compareByDescending<GroceryItem> { DateUtils.isExpiringWithin(it.expiryDate, expiryWarningDays) }
                .thenBy { it.expiryDate ?: Long.MAX_VALUE }
        )
        val ingredientDtos = sortedInventory.map {
            RecipeIngredientDto(
                itemId = it.id,
                name = it.name,
                quantity = it.quantity.toDouble(),
                unit = "pcs",
                isExpiringSoon = DateUtils.isExpiringWithin(it.expiryDate, expiryWarningDays)
            )
        }
        val request = RecipeRequest(
            availableIngredients = ingredientDtos,
            pantryStaplesIncluded = false,
            pantryStaples = emptyList(),
            prioritizeExpiringSoon = prioritizeExpiringSoon,
            minMatchPercent = minMatchPercent,
            maxRecipes = MAX_AI_RECIPES,
            userPrompt = userPrompt
        )

        val service = aiApiService ?: return generateLocalFallbackRecipes(
            inventory = sortedInventory,
            userPrompt = userPrompt,
            expiryWarningDays = expiryWarningDays
        )
        val recipes = try {
            val response = service.generateRecipes(request)
            val recipeDtos = response.availableRecipes.ifEmpty { response.recipes }
            recipeDtos.mapIndexedNotNull { index, dto ->
                dto.toStrictRecipe(index, sortedInventory)
            }
        } catch (error: HttpException) {
            generateLocalFallbackRecipes(
                inventory = sortedInventory,
                userPrompt = userPrompt,
                expiryWarningDays = expiryWarningDays
            )
        } catch (error: IOException) {
            generateLocalFallbackRecipes(
                inventory = sortedInventory,
                userPrompt = userPrompt,
                expiryWarningDays = expiryWarningDays
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            generateLocalFallbackRecipes(
                inventory = sortedInventory,
                userPrompt = userPrompt,
                expiryWarningDays = expiryWarningDays
            )
        }

        return recipes
    }

    private fun com.xmum.smartgrocery.data.network.dto.AiRecipeDto.toStrictRecipe(
        index: Int,
        inventory: List<GroceryItem>
    ): Recipe? {
        val availableByName = inventory.associateBy { it.name.normalizedIngredientName() }
        val name = foodName ?: title ?: this.name ?: "Generated Recipe"
        val usedNames = ingredientsUsed.ifEmpty {
            ingredientsNeeded.ifEmpty { ingredients }.map { it.name }
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedIngredientName() }

        if (usedNames.isEmpty()) return null

        val usedItems = usedNames.map { usedName ->
            availableByName[usedName.normalizedIngredientName()] ?: return null
        }
        val ingredientDtos = ingredientsNeeded.ifEmpty { ingredients }
        val recipeIngredients = usedItems.map { item ->
            val dto = ingredientDtos.firstOrNull { dto ->
                dto.name.normalizedIngredientName() == item.name.normalizedIngredientName()
            }
            RecipeIngredient(
                name = item.name,
                quantityRequired = dto?.amountDeduct?.coerceAtLeast(0.0)?.takeIf { it > 0.0 } ?: 1.0,
                unit = dto?.unit?.takeIf { it.isNotBlank() } ?: "pcs",
                optional = false
            )
        }
        val steps = steps.ifEmpty { instructions }
            .filter { step -> step.onlyMentionsAllowedIngredients(usedItems.map { it.name }) }
            .ifEmpty { buildStrictSteps(name, usedItems.map { it.name }) }

        return Recipe(
            id = id ?: recipeId?.filter { it.isDigit() }?.toIntOrNull() ?: (REMOTE_RECIPE_ID_OFFSET + index),
            name = name,
            ingredients = recipeIngredients,
            instructions = steps,
            imageUrl = imageUrl.ifBlank { imageUrlForKeyword(imageSearchKeyword ?: "$name dish photo") },
            videoUrl = videoUrl,
            prepTimeMinutes = prepTimeMinutes
        )
    }

    private fun generateLocalFallbackRecipes(
        inventory: List<GroceryItem>,
        userPrompt: String?,
        expiryWarningDays: Int
    ): List<Recipe> {
        if (inventory.isEmpty()) return emptyList()

        val focusTerms = extractFocusTerms(userPrompt)
        val prioritizedItems = inventory
            .sortedWith(
                compareByDescending<GroceryItem> { DateUtils.isExpiringWithin(it.expiryDate, expiryWarningDays) }
                    .thenBy { it.expiryDate ?: Long.MAX_VALUE }
            )
            .let { items ->
                if (focusTerms.isEmpty()) {
                    items
                } else {
                    items.sortedByDescending { item ->
                        focusTerms.any { term -> item.name.contains(term, ignoreCase = true) }
                    }
                }
            }

        val fallbackRecipes = mutableListOf<Recipe>()
        buildLocalTemplate(
            id = LOCAL_RECIPE_ID_OFFSET,
            title = "Quick ${prioritizedItems.first().name} Stir-Fry",
            items = prioritizedItems.take(3),
            technique = "stir-fry",
            prepTimeMinutes = 18
        )?.let(fallbackRecipes::add)

        buildLocalTemplate(
            id = LOCAL_RECIPE_ID_OFFSET + 1,
            title = "${prioritizedItems.first().name} Pantry Bowl",
            items = prioritizedItems.drop(1).take(2).ifEmpty { prioritizedItems.take(2) },
            technique = "bowl",
            prepTimeMinutes = 15
        )?.let(fallbackRecipes::add)

        buildLocalTemplate(
            id = LOCAL_RECIPE_ID_OFFSET + 2,
            title = "Simple ${prioritizedItems.first().category} Skillet",
            items = prioritizedItems.takeLast(prioritizedItems.size.coerceAtMost(3)).ifEmpty { prioritizedItems.take(1) },
            technique = "skillet",
            prepTimeMinutes = 20
        )?.let(fallbackRecipes::add)

        return fallbackRecipes.distinctBy { it.name }.take(MAX_AI_RECIPES)
    }

    private fun buildLocalTemplate(
        id: Int,
        title: String,
        items: List<GroceryItem>,
        technique: String,
        prepTimeMinutes: Int
    ): Recipe? {
        if (items.isEmpty()) return null

        val mainIngredients = items.map { item ->
            RecipeIngredient(
                name = item.name,
                quantityRequired = 1.0,
                unit = "pcs"
            )
        }
        val itemNames = items.joinToString(", ") { it.name }
        val instructions = when (technique) {
            "stir-fry" -> listOf(
                "Prepare $itemNames into bite-sized pieces.",
                "Heat a pan over medium heat.",
                "Cook the ingredients until tender and lightly browned.",
                "Fold the cooked ingredients together evenly.",
                "Serve warm."
            )
            "bowl" -> listOf(
                "Slice or portion $itemNames into a serving bowl.",
                "Warm any cooked ingredients in a pan for a few minutes.",
                "Toss the prepared ingredients together.",
                "Rest briefly so the textures settle.",
                "Serve as a quick pantry bowl."
            )
            else -> listOf(
                "Prepare $itemNames and pat dry if needed.",
                "Heat a skillet over medium heat.",
                "Cook the firm ingredients first, then add softer items.",
                "Stir until everything is cooked through.",
                "Plate immediately for a simple home meal."
            )
        }

        return Recipe(
            id = id,
            name = title,
            ingredients = mainIngredients,
            instructions = instructions,
            imageUrl = imageForIngredients(items),
            videoUrl = "",
            prepTimeMinutes = prepTimeMinutes
        )
    }

    private fun imageForIngredients(items: List<GroceryItem>): String {
        val names = items.joinToString(" ") { it.name.lowercase(Locale.ROOT) }
        return when {
            "egg" in names && "tomato" in names -> "https://images.unsplash.com/photo-1598214813441-fd04166ecfc6?w=600&auto=format&fit=crop"
            "bread" in names || "avocado" in names -> "https://images.unsplash.com/photo-1719520670204-dbe1903a789f?w=600&auto=format&fit=crop"
            "milk" in names || "pasta" in names -> "https://images.unsplash.com/photo-1645112411341-6c4fd023714a?w=600&auto=format&fit=crop"
            "banana" in names -> "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600&auto=format&fit=crop"
            else -> "https://images.unsplash.com/photo-1547592180-85f173990554?w=600&auto=format&fit=crop"
        }
    }

    private fun extractFocusTerms(prompt: String?): List<String> {
        if (prompt.isNullOrBlank()) return emptyList()
        val stopWords = setOf(
            "suggest", "recommend", "recipe", "recipes", "using", "with", "my", "a", "an",
            "the", "for", "dinner", "lunch", "breakfast", "meal", "meals", "expiring", "soon"
        )
        return prompt
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim().removeSuffix("es").removeSuffix("s") }
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
    }

    private fun String.normalizedIngredientName(): String {
        return lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .removeSuffix("es")
            .removeSuffix("s")
    }

    private fun String.onlyMentionsAllowedIngredients(allowedIngredients: List<String>): Boolean {
        val lowerStep = lowercase(Locale.ROOT)
        val blockedExtras = setOf(
            "salt", "pepper", "oil", "olive oil", "soy sauce", "sauce", "butter",
            "garlic", "onion", "water", "stock", "broth", "sugar", "vinegar",
            "lemon", "lime", "herb", "garnish", "spice", "seasoning"
        )
        val allowed = allowedIngredients.map { it.normalizedIngredientName() }.toSet()
        return blockedExtras.none { extra ->
            extra.normalizedIngredientName() !in allowed && lowerStep.contains(Regex("\\b${Regex.escape(extra)}\\b"))
        }
    }

    private fun buildStrictSteps(recipeName: String, ingredientNames: List<String>): List<String> {
        val itemNames = ingredientNames.joinToString(", ")
        return listOf(
            "Step 1: Prepare $itemNames into bite-sized pieces.",
            "Step 2: Cook the prepared ingredients over medium heat until tender.",
            "Step 3: Combine the cooked ingredients and serve as $recipeName."
        )
    }

    private fun imageUrlForKeyword(keyword: String): String {
        val query = URLEncoder.encode(keyword, "UTF-8")
        return "https://source.unsplash.com/600x400/?$query"
    }

    companion object {
        private const val MAX_AI_RECIPES = 5
        private const val REMOTE_RECIPE_ID_OFFSET = 10_000
        private const val LOCAL_RECIPE_ID_OFFSET = 20_000
    }

    private fun HttpException.readAiErrorMessage(): String {
        val body = response()?.errorBody()?.string().orEmpty()
        val parsed = runCatching { gson.fromJson(body, AiErrorResponse::class.java) }.getOrNull()
        return parsed?.detail
            ?: parsed?.error
            ?: "AI recipe generation failed with HTTP ${code()}."
    }
}

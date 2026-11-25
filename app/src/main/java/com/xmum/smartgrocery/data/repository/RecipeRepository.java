package com.xmum.smartgrocery.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.xmum.smartgrocery.data.local.entity.GroceryItem;
import com.xmum.smartgrocery.data.local.entity.Recipe;
import com.xmum.smartgrocery.data.local.entity.RecipeIngredient;
import com.xmum.smartgrocery.data.network.AiApiService;
import com.xmum.smartgrocery.data.network.AiSystemPrompts;
import com.xmum.smartgrocery.data.network.dto.AiErrorResponse;
import com.xmum.smartgrocery.data.network.dto.AiRecipeDto;
import com.xmum.smartgrocery.data.network.dto.AiRecipeIngredientDto;
import com.xmum.smartgrocery.data.network.dto.CalculatorMissingItemDto;
import com.xmum.smartgrocery.data.network.dto.ChecklistIngredientDto;
import com.xmum.smartgrocery.data.network.dto.InventoryDeductedDto;
import com.xmum.smartgrocery.data.network.dto.RecipeIngredientDto;
import com.xmum.smartgrocery.data.network.dto.RecipeRequest;
import com.xmum.smartgrocery.data.network.dto.RecipeResponse;
import com.xmum.smartgrocery.utils.AndroidCoroutines;
import com.xmum.smartgrocery.utils.DateUtils;
import com.xmum.smartgrocery.utils.GroceryCategories;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import kotlin.coroutines.Continuation;
import kotlin.text.StringsKt;
import okhttp3.ResponseBody;
import retrofit2.HttpException;

public final class RecipeRepository {
    private static final int MAX_AI_RECIPES = 5;
    private static final int REMOTE_RECIPE_ID_OFFSET = 10_000;
    private static final int LOCAL_RECIPE_ID_OFFSET = 20_000;
    private static final Pattern NON_NAME = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "suggest", "recommend", "recipe", "recipes", "using", "with", "my", "a", "an", "the", "for",
            "dinner", "lunch", "breakfast", "meal", "meals", "expiring", "soon"));
    private static final List<String> BLOCKED_EXTRAS = Arrays.asList(
            "salt", "pepper", "oil", "olive oil", "soy sauce", "sauce", "butter", "garlic", "onion",
            "water", "stock", "broth", "sugar", "vinegar", "lemon", "lime", "herb", "garnish", "spice", "seasoning");

    private final AiApiService aiApiService;
    private final Gson gson = new Gson();

    private final List<Recipe> presetRecipes = Collections.unmodifiableList(Arrays.asList(
        new Recipe(
            1,
            "Tomato Scrambled Eggs",
            Arrays.asList(
                new RecipeIngredient("Egg", 3.0, "pcs", false),
                new RecipeIngredient("Tomato", 2.0, "pcs", false),
                new RecipeIngredient("Scallion", 1.0, "stalk", true)
            ),
            Arrays.asList(
                "Whisk the eggs in a bowl with a pinch of salt.",
                "Cut tomatoes into small wedges.",
                "Heat oil in a pan, pour in eggs and scramble gently until 80% cooked. Remove and set aside.",
                "In the same pan, stir-fry tomatoes until soft and juicy.",
                "Return eggs back to the pan, toss with chopped scallions, season with light soy sauce, and serve hot."
            ),
            "https://images.unsplash.com/photo-1598214813441-fd04166ecfc6?w=600&auto=format&fit=crop",
            "https://www.youtube.com/watch?v=1F_4-tGisw4",
            15
        ),
        new Recipe(
            2,
            "Classic Avocado Toast",
            Arrays.asList(
                new RecipeIngredient("Avocado", 1.0, "pc", false),
                new RecipeIngredient("Bread", 2.0, "slices", false),
                new RecipeIngredient("Egg", 1.0, "pc", false)
            ),
            Arrays.asList(
                "Toast the bread slices until golden brown and crispy.",
                "Mash the avocado in a bowl with lemon juice, salt, and pepper.",
                "Fry or poach the egg to your liking.",
                "Spread the mashed avocado evenly over the toasted bread.",
                "Top with the cooked egg and a sprinkle of chili flakes if desired."
            ),
            "https://images.unsplash.com/photo-1719520670204-dbe1903a789f?w=600&auto=format&fit=crop",
            "https://www.youtube.com/watch?v=F5vVfL014a4",
            10
        ),
        new Recipe(
            3,
            "Chicken Creamy Pasta",
            Arrays.asList(
                new RecipeIngredient("Chicken", 200.0, "g", false),
                new RecipeIngredient("Pasta", 150.0, "g", false),
                new RecipeIngredient("Milk", 100.0, "ml", false),
                new RecipeIngredient("Garlic", 2.0, "cloves", true)
            ),
            Arrays.asList(
                "Boil pasta in salted water according to instructions. Drain and set aside.",
                "Sauté minced garlic in olive oil, then add diced chicken breast until cooked.",
                "Pour in milk (or heavy cream if available) and simmer until slightly thickened.",
                "Toss in the cooked pasta and mix well with salt, pepper, and cheese.",
                "Garnish with parsley and enjoy creamy home-cooked pasta."
            ),
            "https://images.unsplash.com/photo-1645112411341-6c4fd023714a?w=600&auto=format&fit=crop",
            "https://www.youtube.com/watch?v=LPE8Ctk9VwQ",
            25
        ),
        new Recipe(
            4,
            "Fluffy Banana Pancakes",
            Arrays.asList(
                new RecipeIngredient("Banana", 1.0, "pc", false),
                new RecipeIngredient("Egg", 2.0, "pcs", false),
                new RecipeIngredient("Milk", 50.0, "ml", false)
            ),
            Arrays.asList(
                "Mash the banana in a bowl until smooth.",
                "Whisk in the eggs and milk until fully combined.",
                "Heat a non-stick pan over medium-low heat with a little butter or oil.",
                "Pour small circles of batter into the pan. Cook until bubbles form, then flip.",
                "Stack them high and serve with syrup, honey, or fresh berries."
            ),
            "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600&auto=format&fit=crop",
            "https://www.youtube.com/watch?v=842eDlyK-Fk",
            18
        ),
        new Recipe(
            5,
            "French Toast",
            Arrays.asList(
                new RecipeIngredient("Bread", 3.0, "slices", false),
                new RecipeIngredient("Milk", 60.0, "ml", false),
                new RecipeIngredient("Egg", 2.0, "pcs", false)
            ),
            Arrays.asList(
                "Whisk eggs, milk, and a pinch of cinnamon together in a shallow dish.",
                "Dip each bread slice into the mixture, coating both sides well.",
                "Melt butter in a skillet over medium heat.",
                "Fry bread slices until golden brown on both sides (2-3 minutes per side).",
                "Serve warm with a drizzle of honey, maple syrup, or a dust of powdered sugar."
            ),
            "https://images.unsplash.com/photo-1484723091739-30a097e8f929?w=600&auto=format&fit=crop",
            "https://www.youtube.com/watch?v=2Tz8X-sR7Dk",
            12
        )
    ));

    public RecipeRepository() {
        this(null);
    }

    public RecipeRepository(@Nullable AiApiService aiApiService) {
        this.aiApiService = aiApiService;
    }

    @NonNull
    public List<Recipe> getPresetRecipes() {
        return presetRecipes;
    }

    @NonNull
    public Object generateChecklistAssistantResult(@NonNull List<GroceryItem> inventory,
            @NonNull String userPrompt, @NonNull Continuation<? super GroceryChecklistResult> completion) {
        return generateChecklistAssistantResult(inventory, userPrompt, 3, completion);
    }

    @NonNull
    public Object generateChecklistAssistantResult(@NonNull List<GroceryItem> inventory,
            @NonNull String userPrompt, int expiryWarningDays,
            @NonNull Continuation<? super GroceryChecklistResult> completion) {
        List<GroceryItem> sorted = readyIngredients(inventory, expiryWarningDays);
        RecipeRequest request = request(sorted, userPrompt, true, 0, 1, expiryWarningDays);
        if (aiApiService == null) {
            throw new IllegalStateException("AI checklist service is not configured. Check AI_BASE_URL.");
        }
        return AndroidCoroutines.<RecipeResponse, GroceryChecklistResult>then(
                next -> AndroidCoroutines.mapFailure(resumed -> aiApiService.generateRecipes(request, resumed),
                        this::mapChecklistError, next),
                (response, unused) -> checklist(response), completion);
    }

    @NonNull
    public Object generateAiRecipes(@NonNull List<GroceryItem> inventory, boolean prioritizeExpiringSoon,
            @NonNull Continuation<? super List<Recipe>> completion) {
        return generateAiRecipes(inventory, prioritizeExpiringSoon, Collections.emptySet(), null, 80, 3, completion);
    }

    @NonNull
    public Object generateAiRecipes(@NonNull List<GroceryItem> inventory, boolean prioritizeExpiringSoon,
            @NonNull Set<String> pantryStaples, @Nullable String userPrompt, int minMatchPercent,
            int expiryWarningDays, @NonNull Continuation<? super List<Recipe>> completion) {
        List<GroceryItem> sorted = readyIngredients(inventory, expiryWarningDays);
        RecipeRequest request = request(sorted, userPrompt, prioritizeExpiringSoon, minMatchPercent,
                MAX_AI_RECIPES, expiryWarningDays);
        if (aiApiService == null) {
            return localRecipes(sorted, userPrompt, expiryWarningDays);
        }
        return AndroidCoroutines.<List<Recipe>>recoverCall(next ->
                AndroidCoroutines.<RecipeResponse, List<Recipe>>then(
                        response -> aiApiService.generateRecipes(request, response),
                        (response, unused) -> strictRecipes(response, sorted), next),
                error -> localRecipes(sorted, userPrompt, expiryWarningDays), completion);
    }

    @NonNull
    public static List<GroceryItem> readyIngredients(@NonNull List<GroceryItem> inventory, int warningDays) {
        List<GroceryItem> ready = new ArrayList<>();
        for (GroceryItem item : inventory) {
            if (item.getQuantity() > 0 && GroceryCategories.isFood(item.getCategory())
                    && !DateUtils.isExpired(item.getExpiryDate())) {
                ready.add(item);
            }
        }
        ready.sort(expiryOrder(warningDays));
        return ready;
    }

    private static Comparator<GroceryItem> expiryOrder(int warningDays) {
        return Comparator.comparing((GroceryItem item) -> DateUtils.isExpiringWithin(item.getExpiryDate(), warningDays))
                .reversed().thenComparingLong(item -> item.getExpiryDate() == null ? Long.MAX_VALUE : item.getExpiryDate());
    }

    private static RecipeRequest request(List<GroceryItem> inventory, String userPrompt, boolean prioritize,
            int minMatch, int maxRecipes, int warningDays) {
        List<RecipeIngredientDto> ingredients = new ArrayList<>();
        for (GroceryItem item : inventory) {
            ingredients.add(new RecipeIngredientDto(item.getId(), item.getName(), item.getQuantity(), "pcs",
                    DateUtils.isExpiringWithin(item.getExpiryDate(), warningDays), false));
        }
        return new RecipeRequest(AiSystemPrompts.RECIPE_GENERATOR, ingredients, false, Collections.emptyList(),
                prioritize, minMatch, maxRecipes, userPrompt);
    }

    private static GroceryChecklistResult checklist(RecipeResponse response) {
        String dish = response.getDishName();
        Integer servings = response.getServings();
        if (dish == null || StringsKt.isBlank(dish)) {
            throw new IllegalStateException("Gemini response missing dish_name.");
        }
        if (servings == null || servings <= 0) {
            throw new IllegalStateException("Gemini response missing valid servings.");
        }

        List<ChecklistIngredientDto> ingredientDtos = orEmpty(response.getIngredients());
        List<String> steps = orEmpty(response.getSteps());
        // Older saved checklists may contain no cooking instructions.
        if ((!ingredientDtos.isEmpty() || !steps.isEmpty())
                && (ingredientDtos.isEmpty() || ingredientDtos.size() > 40 || steps.isEmpty() || steps.size() > 24
                || ingredientDtos.stream().anyMatch(item -> StringsKt.isBlank(item.getName()) || StringsKt.isBlank(item.getAmount()))
                || steps.stream().anyMatch(step -> StringsKt.isBlank(step) || step.length() > 1600))) {
            throw new IllegalStateException("AI returned incomplete cooking instructions. Generate the checklist again.");
        }

        List<MissingChecklistItem> missing = new ArrayList<>();
        for (CalculatorMissingItemDto item : response.getCalculatorMissingChecklist()) {
            missing.add(new MissingChecklistItem(item.getName(), item.getMissingQty(), item.getCategory()));
        }
        List<String> alreadyHave = new ArrayList<>();
        List<DeductedInventoryItem> deducted = new ArrayList<>();
        for (InventoryDeductedDto item : response.getInventoryDeducted()) {
            alreadyHave.add(item.getName() + " " + item.getQtyUsed());
            deducted.add(new DeductedInventoryItem(item.getName(), item.getQtyUsed()));
        }
        List<ChecklistIngredient> ingredients = new ArrayList<>();
        for (ChecklistIngredientDto item : ingredientDtos) {
            ingredients.add(new ChecklistIngredient(item.getName(), item.getAmount(), item.isInInventory()));
        }
        return new GroceryChecklistResult(dish, servings, missing, alreadyHave, deducted, ingredients, steps);
    }

    private static List<Recipe> strictRecipes(RecipeResponse response, List<GroceryItem> inventory) {
        List<AiRecipeDto> recipes = response.getAvailableRecipes().isEmpty() ? response.getRecipes() : response.getAvailableRecipes();
        List<Recipe> converted = new ArrayList<>();
        Map<String, GroceryItem> byName = new HashMap<>();
        for (GroceryItem item : inventory) {
            byName.put(normalizeIngredient(item.getName()), item);
        }
        for (int i = 0; i < recipes.size(); i++) {
            Recipe recipe = strictRecipe(recipes.get(i), i, byName);
            if (recipe != null) {
                converted.add(recipe);
            }
        }
        return converted;
    }

    @Nullable
    private static Recipe strictRecipe(AiRecipeDto recipe, int index, Map<String, GroceryItem> inventory) {
        String name = recipe.getFoodName() != null ? recipe.getFoodName()
                : recipe.getTitle() != null ? recipe.getTitle() : recipe.getName() != null ? recipe.getName() : "Generated Recipe";
        List<AiRecipeIngredientDto> source = recipe.getIngredientsNeeded().isEmpty()
                ? recipe.getIngredients() : recipe.getIngredientsNeeded();
        List<String> itemNames = new ArrayList<>(recipe.getIngredientsUsed());
        if (itemNames.isEmpty()) {
            for (AiRecipeIngredientDto item : source) {
                itemNames.add(item.getName());
            }
        }
        Set<String> usedNames = new LinkedHashSet<>();
        for (String itemName : itemNames) {
            String trimmed = StringsKt.trim(itemName).toString();
            if (!StringsKt.isBlank(trimmed)) {
                usedNames.add(normalizeIngredient(trimmed));
            }
        }
        if (usedNames.isEmpty()) {
            return null;
        }

        Map<String, AiRecipeIngredientDto> ingredientDetails = new HashMap<>();
        for (AiRecipeIngredientDto item : source) {
            ingredientDetails.putIfAbsent(normalizeIngredient(item.getName()), item);
        }
        List<RecipeIngredient> ingredients = new ArrayList<>();
        List<String> matchedNames = new ArrayList<>();
        for (String itemName : usedNames) {
            GroceryItem stock = inventory.get(itemName);
            if (stock == null) {
                return null;
            }
            matchedNames.add(stock.getName());
            AiRecipeIngredientDto details = ingredientDetails.get(normalizeIngredient(stock.getName()));
            double quantity = details != null && details.getAmountDeduct() > 0.0 ? details.getAmountDeduct() : 1.0;
            String unit = details == null || details.getUnit() == null || StringsKt.isBlank(details.getUnit())
                    ? "pcs" : details.getUnit();
            ingredients.add(new RecipeIngredient(stock.getName(), quantity, unit, false));
        }

        List<String> steps = new ArrayList<>();
        List<String> suggested = recipe.getSteps().isEmpty() ? recipe.getInstructions() : recipe.getSteps();
        Set<String> allowed = new HashSet<>();
        for (String matched : matchedNames) {
            allowed.add(normalizeIngredient(matched));
        }
        for (String step : suggested) {
            if (mentionsOnlyAvailable(step, allowed)) {
                steps.add(step);
            }
        }
        if (steps.isEmpty()) {
            steps = defaultSteps(name, matchedNames);
        }

        int id = REMOTE_RECIPE_ID_OFFSET + index;
        if (recipe.getId() != null) {
            id = recipe.getId();
        } else if (recipe.getRecipeId() != null) {
            StringBuilder digits = new StringBuilder();
            for (char c : recipe.getRecipeId().toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                }
            }
            Integer parsed = StringsKt.toIntOrNull(digits.toString());
            if (parsed != null) {
                id = parsed;
            }
        }
        String image = recipe.getImageUrl();
        if (StringsKt.isBlank(image)) {
            String keyword = recipe.getImageSearchKeyword();
            image = imageUrl(keyword == null ? name + " dish photo" : keyword);
        }
        return new Recipe(id, name, ingredients, steps, image, recipe.getVideoUrl(), recipe.getPrepTimeMinutes());
    }

    private static List<Recipe> localRecipes(List<GroceryItem> inventory, String userPrompt, int warningDays) {
        if (inventory.isEmpty()) {
            return Collections.emptyList();
        }
        List<GroceryItem> ordered = new ArrayList<>(inventory);
        ordered.sort(expiryOrder(warningDays));
        Set<String> focus = focusTerms(userPrompt);
        if (!focus.isEmpty()) {
            ordered.sort(Comparator.comparing((GroceryItem item) ->
                    focus.stream().anyMatch(term -> StringsKt.contains(item.getName(), term, true))).reversed());
        }

        GroceryItem first = ordered.get(0);
        List<GroceryItem> firstThree = ordered.subList(0, Math.min(3, ordered.size()));
        List<GroceryItem> nextTwo = ordered.size() > 1 ? ordered.subList(1, Math.min(3, ordered.size())) : firstThree;
        List<GroceryItem> lastThree = ordered.subList(Math.max(0, ordered.size() - 3), ordered.size());
        List<Recipe> templates = Arrays.asList(
                buildTemplate(LOCAL_RECIPE_ID_OFFSET, "Quick " + first.getName() + " Stir-Fry", firstThree, "stir-fry", 18),
                buildTemplate(LOCAL_RECIPE_ID_OFFSET + 1, first.getName() + " Pantry Bowl", nextTwo, "bowl", 15),
                buildTemplate(LOCAL_RECIPE_ID_OFFSET + 2, "Simple " + first.getCategory() + " Skillet", lastThree, "skillet", 20));
        Map<String, Recipe> unique = new LinkedHashMap<>();
        for (Recipe template : templates) {
            unique.putIfAbsent(template.getName(), template);
        }
        return new ArrayList<>(unique.values());
    }

    private static Recipe buildTemplate(int id, String title, List<GroceryItem> items, String technique, int minutes) {
        List<RecipeIngredient> ingredients = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (GroceryItem item : items) {
            ingredients.add(new RecipeIngredient(item.getName(), 1.0, "pcs", false));
            names.add(item.getName());
        }
        String itemNames = join(names, ", ");
        List<String> steps;
        switch (technique) {
            case "stir-fry":
                steps = Arrays.asList("Prepare " + itemNames + " into bite-sized pieces.",
                        "Heat a pan over medium heat.", "Cook the ingredients until tender and lightly browned.",
                        "Fold the cooked ingredients together evenly.", "Serve warm.");
                break;
            case "bowl":
                steps = Arrays.asList("Slice or portion " + itemNames + " into a serving bowl.",
                        "Warm any cooked ingredients in a pan for a few minutes.",
                        "Toss the prepared ingredients together.", "Rest briefly so the textures settle.",
                        "Serve as a quick pantry bowl.");
                break;
            default:
                steps = Arrays.asList("Prepare " + itemNames + " and pat dry if needed.",
                        "Heat a skillet over medium heat.", "Cook the firm ingredients first, then add softer items.",
                        "Stir until everything is cooked through.", "Plate immediately for a simple home meal.");
                break;
        }
        return new Recipe(id, title, ingredients, steps, imageFor(names), "", minutes);
    }

    private static String imageFor(List<String> items) {
        String names = join(items, " ").toLowerCase(Locale.ROOT);
        if (names.contains("egg") && names.contains("tomato")) {
            return "https://images.unsplash.com/photo-1598214813441-fd04166ecfc6?w=600&auto=format&fit=crop";
        }
        if (names.contains("bread") || names.contains("avocado")) {
            return "https://images.unsplash.com/photo-1719520670204-dbe1903a789f?w=600&auto=format&fit=crop";
        }
        if (names.contains("milk") || names.contains("pasta")) {
            return "https://images.unsplash.com/photo-1645112411341-6c4fd023714a?w=600&auto=format&fit=crop";
        }
        if (names.contains("banana")) {
            return "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=600&auto=format&fit=crop";
        }
        return "https://images.unsplash.com/photo-1547592180-85f173990554?w=600&auto=format&fit=crop";
    }

    private static Set<String> focusTerms(@Nullable String userPrompt) {
        if (userPrompt == null || StringsKt.isBlank(userPrompt)) {
            return Collections.emptySet();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String word : SPACE.split(NON_NAME.matcher(userPrompt.toLowerCase(Locale.ROOT)).replaceAll(" "))) {
            String term = removePluralSuffix(StringsKt.trim(word).toString());
            if (term.length() > 2 && !STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static String normalizeIngredient(String name) {
        String normalized = NON_NAME.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return removePluralSuffix(SPACE.matcher(StringsKt.trim(normalized).toString()).replaceAll(" "));
    }

    private static String removePluralSuffix(String text) {
        if (text.endsWith("es")) {
            text = text.substring(0, text.length() - 2);
        }
        return text.endsWith("s") ? text.substring(0, text.length() - 1) : text;
    }

    private static boolean mentionsOnlyAvailable(String step, Set<String> allowed) {
        String lower = step.toLowerCase(Locale.ROOT);
        for (String extra : BLOCKED_EXTRAS) {
            if (!allowed.contains(normalizeIngredient(extra))
                    && Pattern.compile("\\b" + Pattern.quote(extra) + "\\b").matcher(lower).find()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> defaultSteps(String name, List<String> ingredients) {
        return Arrays.asList("Step 1: Prepare " + join(ingredients, ", ") + " into bite-sized pieces.",
                "Step 2: Cook the prepared ingredients over medium heat until tender.",
                "Step 3: Combine the cooked ingredients and serve as " + name + ".");
    }

    private static String join(List<String> words, String separator) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                result.append(separator);
            }
            result.append(words.get(i));
        }
        return result.toString();
    }

    private static <T> List<T> orEmpty(@Nullable List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private static String imageUrl(String keyword) {
        try {
            return "https://source.unsplash.com/600x400/?" + URLEncoder.encode(keyword, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    private Throwable mapChecklistError(Exception error) {
        if (!(error instanceof HttpException)) {
            return error;
        }
        HttpException failed = (HttpException) error;
        ResponseBody body = failed.response() == null ? null : failed.response().errorBody();
        try {
            String content = body == null ? "" : body.string();
            AiErrorResponse parsed;
            try {
                parsed = gson.fromJson(content, AiErrorResponse.class);
            } catch (RuntimeException invalid) {
                parsed = null;
            }
            String message = "AI recipe generation failed with HTTP " + failed.code() + ".";
            if (parsed != null && parsed.getDetail() != null) {
                message = parsed.getDetail();
            } else if (parsed != null && parsed.getError() != null) {
                message = parsed.getError();
            }
            return new IOException(message, error);
        } catch (IOException readError) {
            return readError;
        }
    }

    public static final class GroceryChecklistResult {
        private final String dishName;
        private final int servings;
        private final List<MissingChecklistItem> missingChecklist;
        private final List<String> alreadyHave;
        private final List<DeductedInventoryItem> inventoryDeducted;
        private final List<ChecklistIngredient> ingredients;
        private final List<String> steps;

        public GroceryChecklistResult(@NonNull String dishName, int servings, @NonNull List<MissingChecklistItem> missingChecklist, @NonNull List<String> alreadyHave) {
            this(dishName, servings, missingChecklist, alreadyHave, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        public GroceryChecklistResult(@NonNull String dishName, int servings, @NonNull List<MissingChecklistItem> missingChecklist, @NonNull List<String> alreadyHave, @NonNull List<DeductedInventoryItem> inventoryDeducted) {
            this(dishName, servings, missingChecklist, alreadyHave, inventoryDeducted, Collections.emptyList(), Collections.emptyList());
        }

        public GroceryChecklistResult(@NonNull String dishName, int servings, @NonNull List<MissingChecklistItem> missingChecklist, @NonNull List<String> alreadyHave, @NonNull List<DeductedInventoryItem> inventoryDeducted, @NonNull List<ChecklistIngredient> ingredients) {
            this(dishName, servings, missingChecklist, alreadyHave, inventoryDeducted, ingredients, Collections.emptyList());
        }

        public GroceryChecklistResult(@NonNull String dishName, int servings,
                @NonNull List<MissingChecklistItem> missingChecklist,
                @NonNull List<String> alreadyHave,
                @NonNull List<DeductedInventoryItem> inventoryDeducted,
                @NonNull List<ChecklistIngredient> ingredients,
                @NonNull List<String> steps) {
            this.dishName = dishName;
            this.servings = servings;
            this.missingChecklist = missingChecklist;
            this.alreadyHave = alreadyHave;
            this.inventoryDeducted = inventoryDeducted;
            this.ingredients = ingredients;
            this.steps = steps;
        }

        @NonNull
        public String getDishName() {
            return dishName;
        }

        public int getServings() {
            return servings;
        }

        @NonNull
        public List<MissingChecklistItem> getMissingChecklist() {
            return missingChecklist;
        }

        @NonNull
        public List<String> getAlreadyHave() {
            return alreadyHave;
        }

        @NonNull
        public List<DeductedInventoryItem> getInventoryDeducted() {
            return inventoryDeducted;
        }

        @NonNull
        public List<ChecklistIngredient> getIngredients() {
            return ingredients;
        }

        @NonNull
        public List<String> getSteps() {
            return steps;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GroceryChecklistResult)) {
                return false;
            }
            GroceryChecklistResult that = (GroceryChecklistResult) other;
            return dishName.equals(that.dishName) && servings == that.servings && missingChecklist.equals(that.missingChecklist)
                    && alreadyHave.equals(that.alreadyHave) && inventoryDeducted.equals(that.inventoryDeducted)
                    && ingredients.equals(that.ingredients) && steps.equals(that.steps);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dishName, servings, missingChecklist, alreadyHave, inventoryDeducted, ingredients, steps);
        }
    }

    public static final class ChecklistIngredient {
        private final String name;
        private final String amount;
        private final boolean isInInventory;

        public ChecklistIngredient(@NonNull String name, @NonNull String amount,
                boolean isInInventory) {
            this.name = name;
            this.amount = amount;
            this.isInInventory = isInInventory;
        }

        @NonNull
        public String getName() {
            return name;
        }

        @NonNull
        public String getAmount() {
            return amount;
        }

        public boolean isInInventory() {
            return isInInventory;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ChecklistIngredient)) {
                return false;
            }
            ChecklistIngredient that = (ChecklistIngredient) other;
            return name.equals(that.name) && amount.equals(that.amount) && isInInventory == that.isInInventory;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, amount, isInInventory);
        }
    }

    public static final class MissingChecklistItem {
        private final String item;
        private final String neededQuantity;
        private final String category;

        public MissingChecklistItem(@NonNull String item, @NonNull String neededQuantity,
                @NonNull String category) {
            this.item = item;
            this.neededQuantity = neededQuantity;
            this.category = category;
        }

        @NonNull
        public String getItem() {
            return item;
        }

        @NonNull
        public String getNeededQuantity() {
            return neededQuantity;
        }

        @NonNull
        public String getCategory() {
            return category;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MissingChecklistItem)) {
                return false;
            }
            MissingChecklistItem that = (MissingChecklistItem) other;
            return item.equals(that.item) && neededQuantity.equals(that.neededQuantity) && category.equals(that.category);
        }

        @Override
        public int hashCode() {
            return Objects.hash(item, neededQuantity, category);
        }
    }

    public static final class DeductedInventoryItem {
        private final String item;
        private final String usedQuantity;

        public DeductedInventoryItem(@NonNull String item, @NonNull String usedQuantity) {
            this.item = item;
            this.usedQuantity = usedQuantity;
        }

        @NonNull
        public String getItem() {
            return item;
        }

        @NonNull
        public String getUsedQuantity() {
            return usedQuantity;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DeductedInventoryItem)) {
                return false;
            }
            DeductedInventoryItem that = (DeductedInventoryItem) other;
            return item.equals(that.item) && usedQuantity.equals(that.usedQuantity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(item, usedQuantity);
        }
    }
}

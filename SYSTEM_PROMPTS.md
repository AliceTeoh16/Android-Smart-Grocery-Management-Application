# Smart Grocery Management Application - System Prompts Specification

This document contains the production-ready system prompts and JSON schemas required for the AI modules of the Smart Grocery Management System.

---

## 1. AI Recipe Generator Prompt

Use this prompt as the `system_instruction` value when calling the recipe generation endpoint.

```text
You are an expert culinary assistant integrated into a Smart Grocery Management application. Your objective is to generate practical, delicious recipes prioritizing expiring ingredients while minimizing missing extra purchases.

Strict Output Guidelines:
1. You MUST respond ONLY in valid, parsable JSON without markdown formatting or introductory text.
2. Prioritize items marked `is_expiring_soon: true`.
3. Standard pantry staples (e.g., salt, cooking oil, soy sauce, pepper) can be assumed available if `pantry_staples_included` is `true`.
4. Ingredients in `ingredients_needed` must clearly distinguish whether they map directly to available stock items to enable auto-deduction.
```

### Request Payload

```json
{
  "system_instruction": "...",
  "available_ingredients": [
    {
      "item_id": 101,
      "name": "Egg",
      "quantity": 4.0,
      "unit": "pcs",
      "is_expiring_soon": true,
      "is_staple": false
    }
  ],
  "pantry_staples_included": true,
  "pantry_staples": ["Salt", "Pepper", "Cooking Oil"],
  "prioritize_expiring_soon": true,
  "min_match_percent": 80,
  "max_recipes": 5,
  "user_prompt": "quick dinner"
}
```

### Response Payload

```json
{
  "recipes": [
    {
      "recipe_id": "rec_001",
      "title": "Tomato Scrambled Eggs",
      "description": "A quick, classic stir-fry dish utilizing fresh tomatoes and eggs.",
      "prep_time_minutes": 15,
      "ingredients_needed": [
        {
          "item_id": 101,
          "name": "Egg",
          "amount_deduct": 3.0,
          "unit": "pcs",
          "is_main_ingredient": true
        }
      ],
      "instructions": [
        "Beat 3 eggs in a bowl with a pinch of salt."
      ]
    }
  ]
}
```

---

## 2. Voice Input Natural Language Parser Prompt

Use this prompt only if speech parsing is moved to an AI/NLP endpoint. The app also keeps a local parser for offline operation.

```text
You are an intent parser for a grocery inventory system. Parse spoken human language into structured inventory operations. You MUST respond ONLY in valid JSON without markdown formatting or introductory text.
```

### Example

Input:

```text
I used 2 eggs and 1 tomato
```

Output:

```json
{
  "intent": "DEDUCT_INVENTORY",
  "items": [
    {
      "name": "Egg",
      "quantity": 2.0,
      "unit": "pcs"
    },
    {
      "name": "Tomato",
      "quantity": 1.0,
      "unit": "pcs"
    }
  ]
}
```

# Smart Grocery AI Backend

This backend keeps the Gemini API key off the Android device. The app calls `/recipes/generate` for one synchronized payload containing its grocery checklist, complete ingredient plan and cooking steps.

Photo recognition is open-vocabulary: product examples and local category keywords are not a name allowlist. Any visibly recognizable product belonging to Food & Beverage, Household Essentials or Personal Care can be returned, including unfamiliar brands and unlisted products. A useful generic product name is allowed when the brand or exact variety is uncertain. This scope does not guarantee that every item will be recognized in every photo; users still review suggestions and can add missed items.

The Android Add Item photo batch feature calls `/ingredients/scan` with a compressed JPEG in `image_base64` and `mime_type: "image/jpeg"`. Gemini identifies visible food, beverages, household essentials and personal care products by appearance, including unlabelled produce. Watermarks, filenames and generic image labels are excluded. The API returns `items` with specific item `name`, main `category`, nullable `subcategory`, and a nullable visible whole-item `quantity`; no expiry dates or prices are inferred by AI. Photos are forwarded to Gemini and are not written to server storage. This feature needs a running backend, network access and the existing server-side Gemini key. Restart the backend after updating.

Every result, including a single item, opens **Batch Review Checklist**. Rows start checked; users can edit names/categories/counts, review, set or clear optional expiry dates, delete false positives, and add missed items manually. Nothing writes to Room before **Add All to Inventory**. One local transaction inserts all checked rows and their inventory logs, then the existing Room flows refresh Inventory. Prices default to zero. Food uses local expiry estimates and a low-stock threshold of 2; household essentials and personal care default to no expiry and a threshold of 1. Explicitly selected or cleared expiry dates survive review edits and recreation. The photo batch path no longer uses OCR or single-item form autofill.

The shared category hierarchy is **Food & Beverage** (Produce, Meat & Seafood, Dairy, Bakery, Pantry, Spices, Beverages, Snacks & Sweets, Frozen Foods), **Household Essentials** (no subcategories), and **Personal Care** (no subcategories). Beverages, Snacks & Sweets, and Frozen Foods have their own food subcategories. The Android Room v8 migration preserves existing food detail, inventory/history links and spending while splitting category/subcategory and allowing null expiry dates. Undated items are excluded from expiry warnings and expired-item cleanup, and sort after dated batches. Recipe suggestions use food inventory only. Category tags use consistent sage, blue and lilac palettes across Inventory, Shopping List and Budget Tracker.

Household recognition includes cleaning, laundry, kitchen, storage, bathroom and small home supplies (including batteries, light bulbs, candles and wraps). Personal Care includes hygiene, bathing, skincare, haircare, oral care, shaving, grooming, cosmetics, fragrance, menstrual products and related tools. Menstrual cups and makeup sponges are Personal Care; soap dispensers and toothbrush holders are Household Essentials.

Reusable household drinkware and kitchen essentials are also supported: teacups, mugs, drinking glasses, plates, bowls, cutlery and food containers. An empty teacup is Household Essentials with no expiry or subcategory; tea as a drink is Food & Beverage / Beverages. The scanner must not infer a drink from an empty cup. If a normalized visual result is empty, the backend performs one focused recheck of the same pixels within the original scan deadline. Genuinely empty/unsupported images remain empty. Each model attempt has a 20-second maximum within the overall 45-second scan deadline. Slow, unavailable or quota-limited models can fall back to the other configured models; the empty-result recheck starts with a model that responded successfully. The deadline is enforced even if a provider ignores cancellation.

Reusable household cleaning tools are supported, including gloves, cloths, dustpans, brushes, bins and plungers. Food does not need to be present in the photo. Packaged multipacks count as packages; printed pack counts and capacities are not visible-item quantities.

After scanner prompt or schema changes, restart an existing `npm start` / `node server.js` process; it keeps the previous module in memory until restarted. For development, `npm run dev` watches file changes. Check `GET /ingredients/scan/capabilities` or `/health` → `inventoryScan`: the revision must be `household-inventory-v6`, with all three categories and `householdCleaningTools: true`. A successful `/health` response without these fields can come from the old food-only server.

Voice defaults and photo-response normalization use matching ordered product rules. Cleaning tools stay in Household Essentials; personal care products have no food subcategory. Specific products take precedence over ingredient words (orange juice → Beverages, cream crackers → Snacks & Sweets, frozen chicken → Frozen Foods). Visual frozen-food labels are retained when the product name alone cannot establish storage. These corrections apply only to new recognition suggestions; saved rows and user-reviewed categories are preserved.

Run scanner and voice API validation with `node --test ingredient-scan.test.js voice-transcription.test.js`. The scanner tests and Android `InventoryInputCategoryMappingTest` share the 186-product multilingual corpus in `app/src/test/resources/inventory-category-cases.json` and 20 deliberately unlisted products in `app/src/test/resources/unlisted-recognition-cases.json` (relative to the repository root). A malformed detection is skipped without discarding other valid products from the same scan.

## Setup

```powershell
cd backend
npm install
Copy-Item .env.example .env
```

Edit `.env` and set:

```text
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-3.5-flash-lite
GEMINI_FALLBACK_MODELS=gemini-3.5-flash-lite,gemini-3.1-flash-lite,gemini-flash-lite-latest,gemini-3.8-flash
PORT=8787
```

Generated responses extend the existing checklist JSON shape (the existing field names remain compatible):

```json
{
  "dish_name": "Tomato Beef Noodles",
  "servings": 4,
  "missing_checklist": [
    {
      "name": "Beef Slices",
      "missing_qty": "400g",
      "category": "Meat"
    },
    {
      "name": "Noodles",
      "missing_qty": "400g",
      "category": "Pantry"
    }
  ],
  "inventory_deducted": [
    {
      "name": "Tomato",
      "qty_used": "4 pcs (Fully Covered)"
    }
  ],
  "ingredients": [
    { "name": "Beef Slices", "amount": "400g", "is_in_inventory": false },
    { "name": "Noodles", "amount": "400g", "is_in_inventory": false },
    { "name": "Tomato", "amount": "4 pcs", "is_in_inventory": true }
  ],
  "steps": [
    "Slice the tomatoes and cook in a non-stick pan, then add the beef slices and cook through.",
    "Add the fresh noodles and toss until cooked and hot."
  ],
  "step_ingredients": [["Tomato", "Beef Slices"], ["Noodles"]]
}
```

Gemini creates one recipe plan for the requested dish and servings, then derives both the checklist and instructions from it. `ingredients` contains total recipe amounts; `missing_checklist` retains shortage amounts and `inventory_deducted` retains covered usage. `is_in_inventory` is derived from those existing arrays and is true only for fully covered items; a contradictory redundant AI flag cannot reject or change the checklist. The parallel `step_ingredients` array links each instruction to declared ingredient names (empty for equipment-only steps). `checklist-steps.js` aligns harmless Chinese annotations and conservative plurals, while preserving distinct foods and variety qualifiers. It still rejects genuinely omitted/unlisted ingredients, duplicates, invalid amounts or instructions. Invalid output gets at most one corrective generation within the same 45-second request deadline, then a clear retry message. Provider failures retain their existing model fallback behavior. The existing serving-word and quantity validation remains active, with up to 6144 output tokens.

On Android, **View Recipe Steps** expands beneath the existing dish title card. The ingredient checklist, shopping target flow, and Mark as cooked callbacks are unchanged. Steps, ingredient metadata, original prompt and exact checkbox/shopping choices are stored in a complete Room v9 history snapshot. The additive v8 -> v9 migration preserves existing data and backfills matching legacy metadata on first use. History keeps the latest ten generations by timestamp, allows repeated recipe names and opens a chosen snapshot directly in AI Checklist without another API call. Older checklists show a request to generate a new checklist instead of invented instructions. Viewing, scrolling or collapsing steps never updates stock or shopping data.

Test with `node --test ingredient-scan.test.js voice-transcription.test.js checklist-steps.test.js`. Android tests cover response mapping, legacy backfill, atomic ten-entry retention, migration, duplicate-name restoration, checkbox persistence, zero-API history navigation, in-flight generation cancellation, expansion, scrolling, rotation, and read-only viewing.

Then start the server:

```powershell
npm start
```

The Android emulator can reach your computer at:

```text
http://10.0.2.2:8787/
```

That is the default `AI_BASE_URL` used by the Android build. For a physical Android phone, use your computer's LAN IP instead:

```powershell
.\gradlew.bat assembleDebug -PAI_BASE_URL=http://192.168.1.25:8787/
```

Replace `192.168.1.25` with your actual computer IP address.

The nine food subcategories use the existing nullable TEXT subcategory columns in Room v8; no table rebuild, ordinal TypeConverter change or version bump is required. Existing v8 rows keep their saved categories. For older databases upgrading to v8, legacy Beverages and Snacks now retain their meaning as Beverages and Snacks & Sweets instead of being merged into Pantry.

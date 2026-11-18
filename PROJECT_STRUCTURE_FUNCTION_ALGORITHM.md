# ShelfWise: Project Structure, Functions, and Algorithms

This document describes the current implementation of the ShelfWise Android application and its Gemini backend. The Android package remains `com.xmum.smartgrocery`; existing `SmartGrocery` and `PantryPal` source names have been retained.

**Last updated:** 2026-10-30

## 1. Project Purpose

ShelfWise is a local-first grocery management app. It helps a user:

- Track grocery inventory, quantities, prices, categories, and expiry dates.
- Identify low-stock and expiring items.
- Generate an AI grocery checklist from a natural-language dish request.
- Compare AI-required ingredients with usable inventory.
- Add selected missing ingredients to a personal or household shopping list.
- Mark shopping items as purchased and move them into inventory.
- Track monthly spending and compare an estimated shopping cart with the remaining budget.
- Deduct inventory when a checklist meal is marked as cooked.
- Use English, Malay, or Mandarin voice input for multiple grocery actions, with review and confirmation before changes.
- Recognize foods in camera/gallery photos, review an editable batch checklist, and insert selected items into inventory together.

The app stores its operational data locally in Room. The Node.js backend calls Google Gemini for checklist generation, visual food recognition, and voice transcription. These AI operations require a network connection; the backend does not own the inventory database.

## 2. High-Level Architecture

```text
Android application
  |
  | Fragment-hosted Jetpack Compose + retained XML bindings/adapters
  v
ViewModels and Kotlin Flow state
  |
  v
Repositories
  |                         \
  |                          \ Retrofit JSON requests (checklist/photo/audio)
  v                           v
Room SQLite database       Node.js / Express backend
  |                           |
  |                           v
  |                     Google Gemini API
  |
  v
Inventory, shopping list, budget, and checklist UI updates
```

### 2.1 Technology Stack

**Android client**

- Kotlin and Gradle Kotlin DSL.
- MVVM architecture.
- Jetpack Compose Material 3 screens hosted by existing Fragments, alongside retained XML layouts and View Binding.
- Material Components, ConstraintLayout, RecyclerView, BottomSheetDialog, and NestedScrollView.
- Compiled Compose source files under `compose-pages/` for ShelfWise screens; the internal package is still `ui.pantrypal`.
- Room 2.6.1 for local SQLite persistence.
- Kotlin Coroutines and Flow/StateFlow for asynchronous work and reactive UI updates.
- Retrofit 2.11.0, OkHttp, and Gson for checklist, photo recognition, and audio transcription requests.
- Glide 4.16.0 for image loading.
- ZXing 3.5.3 for household shopping-list QR links.
- Android AudioRecord for bounded in-memory microphone capture, with Gemini transcription as the default voice path.
- Android SpeechRecognizer retained as a device-service fallback; automatic language switching depends on Android/provider support.
- Activity Result camera/gallery contracts and FileProvider for ingredient photos. The current visual recognition path uses Gemini, not ML Kit OCR.

**Backend**

- Node.js 20 or newer.
- Express 4.21.2.
- `@google/genai` for Gemini requests.
- `dotenv` for environment variables.
- `cors` and Express JSON middleware.

**External AI provider**

- Google Gemini through the backend.
- The backend default model is `gemini-3.5-flash-lite`, with configurable fallback models.
- The backend keeps `GEMINI_API_KEY` off the Android device.

### 2.2 Android Runtime Configuration

- `applicationId`: `com.xmum.smartgrocery`
- Launcher label: `ShelfWise` (`@string/app_name`).
- `compileSdk`: 34
- `minSdk`: 24
- `targetSdk`: 34
- Default AI base URL: `http://10.0.2.2:8787/`
- `10.0.2.2` points from the Android emulator to the host computer's localhost.

Required Android permissions:

- `INTERNET` for backend requests.
- `RECORD_AUDIO` for voice input.
- `POST_NOTIFICATIONS` for Android 13+ notification permission.
- `RECEIVE_BOOT_COMPLETED` so inventory alert scheduling can be restored after device restart.

Camera capture is delegated to an external camera app using `TakePicture`; gallery selection uses `GetContent`. The manifest declares a non-exported FileProvider at `${applicationId}.ingredientphotos` and does not request broad photo-library access or the `CAMERA` permission.

## 3. Repository Structure

```text
FYP/
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/xmum/smartgrocery/
        SmartGroceryApp.kt
        data/
          local/
            AppDatabase.kt
            dao/
              BudgetDao.kt
              ChecklistHistoryDao.kt
              GroceryDao.kt
              ShoppingListDao.kt
            entity/
              BudgetEntry.kt
              ChecklistHistoryItem.kt
              GroceryItem.kt
              InventoryLog.kt
              Recipe.kt
              ShoppingListItem.kt
          network/
            AiApiService.kt
            AiRecipeClient.kt
            AiSystemPrompts.kt
            dto/RecipeRequest.kt
            dto/RecipeResponse.kt
          repository/
            AppPreferencesRepository.kt
            BudgetRepository.kt
            ChecklistHistoryRepository.kt
            GroceryRepository.kt
            RecipeRepository.kt
            ShoppingListRepository.kt
        ui/
          auth/
          budget/
          common/
          inventory/
          main/
            MainActivity.kt
            MainViewModel.kt
            VoiceAudioCapture.kt
            GeminiVoiceTranscription.kt
            VoiceCommandConfirmationDialog.kt
            VoiceInputPreferences.kt
            VoiceRecognitionRequest.kt
          pantrypal/
          quickadd/
            QuickAddBottomSheetDialog.kt
            IngredientScanBottomSheetDialog.kt
            IngredientScanViewModel.kt
            VisualFoodRecognition.kt
            VoiceInputHandler.kt
          recipes/
          shopping/
        utils/
          DateUtils.kt
          ExpiryEstimator.kt
          GroceryCategories.kt
          IngredientTextParser.kt
          VoiceParser.kt
          VoiceCommandReview.kt
          ShoppingVoiceMerge.kt
          VoiceInventoryItemDraft.kt
          VoiceLanguage.kt
          MalayVoiceSupport.kt
          VoiceRecognitionFailure.kt
      res/
        layout/
        drawable/
        menu/
        values/
        xml/ingredient_scan_paths.xml
    src/test/java/com/xmum/smartgrocery/
      utils/                     # Parsing, multilingual commands, expiry, review validation
      ui/main/                   # PCM/WAV encoding
      ui/recipes/                # Matching and checklist lifecycle
      ui/pantrypal/              # Home overview mapping
    src/androidTest/
      AndroidManifest.xml
      assets/database/schema-v6.sql # Archived schema for the v6 -> v7 migration test
      assets/voice/              # Synthetic English/Mandarin fixtures, test APK only
      java/com/xmum/smartgrocery/ui/
        main/                    # Confirmation, recognition, Gemini, microphone diagnostics
        quickadd/                # Photo selection/review and atomic batch insertion
  backend/
    server.js
    ingredient-scan.js
    ingredient-scan.test.js
    voice-transcription.js
    voice-transcription.test.js
    package.json
    README.md
  compose-pages/
    com/xmum/smartgrocery/ui/pantrypal/
      InventoryScreen.kt
      ShoppingListScreen.kt
      AiChecklistScreen.kt
      BudgetTrackerScreen.kt
      ChecklistHistoryScreen.kt
      AddGroceryItemScreen.kt
      InventoryFeatureNavHost.kt
      ShareAndConnectScreen.kt
      ProfileSettingsScreen.kt
      BatchIngredientReviewSheet.kt
      VoiceInventoryItemForm.kt
      AddShoppingItemSheet.kt
      PurchaseConfirmationDialogContent.kt
      PantryPalTheme.kt
      FeatureComponents.kt
      LegacyUiState.kt
      ...
  tools/
    Start-VoiceEmulator.ps1
  PROJECT_STRUCTURE_FUNCTION_ALGORITHM.md
  END_TO_END_ARCHITECTURE_WORKFLOW.md
  SYSTEM_PROMPTS.md
  frontend.md
  backend.md
```

`app/build.gradle.kts` adds `../compose-pages` as an additional Kotlin source directory. These Composables are part of the app, not a separate demo. Existing Fragments retain ViewModels, bindings, listeners, and adapters, and bridge them into the Compose layouts through helpers such as `rememberBoundText` and `BoundLegacyView`.

Build output folders such as `app/build/`, `build/`, `.gradle/`, and `backend/node_modules/` are generated artifacts, not application source architecture.

## 4. Application Startup and Dependency Wiring

File: `app/src/main/java/com/xmum/smartgrocery/SmartGroceryApp.kt`

At application startup:

1. `SmartGroceryApp` lazily creates the Room database.
2. It creates repositories around the Room DAOs.
3. It creates the Retrofit AI service using `BuildConfig.AI_BASE_URL`.
4. It exposes repositories to the ViewModels.
5. It schedules inventory alerts through `InventoryAlertReceiver.schedule(this)`.

The main screen is `MainActivity`. It manages bottom navigation, the welcome/home flow through `PantryPalFragment`, and Inventory, AI Checklist, Shopping List, and Budget Fragments. It also coordinates voice capture and the confirmation dialog.

`InventoryFeatureNavHost` owns the `inventory` and `inventory/add-grocery-item` Compose routes. Add Item is full screen and embeds the existing `QuickAddBottomSheetDialog` form owner without displaying it as a dialog. Edit Item retains the dialog flow. Profile and Share & Connect entry points bridge to the existing screen/dialog owners.

## 5. Local Data Architecture

File: `app/src/main/java/com/xmum/smartgrocery/data/local/AppDatabase.kt`

The Room database is named `smart_grocery_database` and is currently version **9**. Migration 6 -> 7 removes the obsolete `saved_recipes` table; migration 7 -> 8 preserves inventory/history links while adding category/subcategory and nullable expiry. The five active feature tables remain in use. Migration 8 -> 9 adds a versioned `stateJson` payload to `checklist_history` and evicts entries beyond the latest ten. No new table is created.

### 5.1 Tables and Entities

| Table | Entity | Purpose |
| --- | --- | --- |
| `grocery_items` | `GroceryItem` | Current inventory rows. |
| `budget_entries` | `BudgetEntry` | Monthly budget limit keyed by `yyyy-MM`. |
| `inventory_logs` | `InventoryLog` | Audit trail for additions and deductions. |
| `shopping_list_items` | `ShoppingListItem` | Personal/household shopping entries. |
| `checklist_history` | `ChecklistHistoryItem` | Persistent AI checklist history and status. |

### 5.2 Inventory Schema

`GroceryItem` contains:

```text
id, name, quantity, price, category,
expiryDate, threshold, purchasedDate
```

Different batches of the same food are stored as separate `GroceryItem` rows. The Inventory ViewModel groups rows by normalized item name and presents total quantity, earliest expiry, and batch details. This is a multi-batch model implemented through multiple rows, rather than a `batches` JSON field inside one entity.

### 5.3 Shopping List Schema

`ShoppingListItem` contains:

```text
id, sourceItemId, itemName, category,
quantityNeeded, quantityLabel, estimatedUnitPrice,
addedBy, assignedTo, isChecked, createdAt, updatedAt
```

`sourceItemId` links an automatic low-stock entry to its inventory item. AI checklist entries are inserted as manual-style entries with no source item ID.

### 5.4 Checklist Persistence

`ChecklistHistoryItem` stores:

```text
id, dishName, servings, status, created_at,
missingChecklistJson, inventoryDeductedJson,
addedShoppingKeysJson, stateJson
```

Supported statuses are `pending`, `cooked`, and `cancelled`. `ChecklistHistorySnapshot.kt` encodes the complete `SavedChecklist`: recipe name, servings, original prompt, ingredient amounts and stock flags, missing/covered arrays, cooking steps, shopping markers, and unchecked keys for both missing and in-stock ingredients. Each generation gets `meal_<timestamp>_<UUID>` as its identity and the same timestamp in `created_at`; repeated dish names and even same-millisecond generations remain independent.

`ChecklistHistoryDao.saveWithPruning()` inserts and evicts older rows in one Room transaction, ordering by `created_at DESC, id DESC` and keeping at most ten. Updating an existing record at capacity does not evict another entry. Checkbox changes and shopping/status transitions update the same snapshot without changing its generation time.

`ChecklistHistoryScreen` shows compact clickable cards, timestamps and status badges with no accordion or inline ingredient/actions area. Tapping a card calls `RecipeViewModel.restoreChecklistById()`, fetches that ID from Room, cancels an in-flight generation and hydrates the main AI Checklist before navigating there through `ChecklistHistoryModal`. Restoration never calls Gemini, inserts a new checklist, or changes inventory/shopping quantities. Cooked/cancelled checklists retain their recorded checks and are read-only.

**Clear All History** is a borderless forest-green text button matching **Voice input**, fixed in a bottom-right footer outside the scrolling history list. The list retains `100.dp` bottom content padding and has its own viewport above the footer, so items cannot be covered by the action. A cream/green confirmation dialog requires an explicit **Clear history** tap. `RecipeViewModel.clearAllChecklistHistory()` cancels in-flight generation, serializes a history-only `DELETE` through the repository/DAO, clears the active checklist/reference and list after success, and prevents legacy preference payloads from restoring deleted history. Failure retains the records and displays an error. Inventory, shopping, logs, budgets and search suggestions are unaffected; no schema migration is needed.

Legacy rows are backfilled from their stored Room ingredient arrays and matching old preference metadata once. Preferences now hold only the active checklist ID and a migration marker; new payloads are stored solely in Room. Old records without saved steps stay readable without inventing instructions. A pruned or missing ID reports that it is no longer in recent history and never triggers generation.

## 6. Main Modules

### 6.1 Inventory Management

Main files:

- `ui/inventory/InventoryFragment.kt`
- `ui/inventory/InventoryViewModel.kt`
- `ui/inventory/InventoryAdapter.kt`
- `data/repository/GroceryRepository.kt`
- `data/local/dao/GroceryDao.kt`

Responsibilities:

- Display active inventory items.
- Search inventory by name.
- Add, edit, increment, decrement, and delete items.
- Group same-name rows into multi-batch cards.
- Filter by All/In Stock, Expiring Soon, and Expired.
- Hide zero-quantity items.
- Hide expired items from the normal in-stock view.
- Show expired-item cleanup and expiry update actions.

Inventory uses FEFO behavior: First Expired, First Out. Its Compose page hosts the existing inventory adapter through an Android view bridge and preserves the quantity controls. The terracotta circular add button remains above bottom navigation; page scrolling and bottom padding protect the final card.

### 6.2 Shopping List

Main files:

- `ui/shopping/ShoppingListFragment.kt`
- `ui/shopping/ShoppingListViewModel.kt`
- `ui/shopping/ShoppingListAdapter.kt`
- `data/repository/ShoppingListRepository.kt`
- `data/local/dao/ShoppingListDao.kt`

Responsibilities:

- Synchronize low-stock inventory into automatic shopping entries.
- Add manual shopping items.
- Receive selected AI checklist items.
- Assign entries to `Me` or `Household`.
- Filter All, Me, and Household entries.
- Accumulate AI checklist quantities into compatible pending shopping rows.
- Ask for the actual purchase price when an item has no price.
- Move purchased items into inventory.
- Recalculate estimated cart and budget impact.

Shopping aggregation rules:

- Recipe measurements, including pantry and seasoning amounts, accumulate within the selected Personal or Household list.
- `ChecklistShoppingMerge.kt` uses the same conservative multilingual name aliases as voice matching. Bought rows and other shopping owners are excluded from merge targets.
- Compatible quantities merge (e.g. 200g + 200g = 400 g); kg/g and L/ml normalize to base units. Incompatible or unquantified measures remain separate instead of replacing an existing amount.
- Quantity labels such as `kg`, `g`, `ml`, `tbsp`, `tsp`, `pcs`, `stalks`, and `cloves` are normalized before merging.

### 6.3 AI Checklist Assistant

Main files:

- `ui/recipes/RecipeFragment.kt`
- `ui/recipes/RecipeViewModel.kt`
- `data/repository/RecipeRepository.kt`
- `data/network/AiApiService.kt`
- `backend/server.js`
- `backend/checklist-steps.js`

The AI Checklist generates its ingredient checklist and cooking instructions together in one request. It receives the complete natural-language request, for example:

```text
I want to cook Tomato Beef Noodles for 4 people
```

The frontend does not split the request with simple regex parsing. The expected result is:

```json
{
  "dish_name": "Tomato Beef Noodles",
  "servings": 4,
  "missing_checklist": [
    { "name": "Beef", "missing_qty": "500g", "category": "Meat" },
    { "name": "Noodles", "missing_qty": "400g", "category": "Pantry" }
  ],
  "inventory_deducted": [
    { "name": "Tomato", "qty_used": "4 pcs (Fully Covered)" }
  ],
  "ingredients": [
    { "name": "Beef", "amount": "500g", "is_in_inventory": false },
    { "name": "Noodles", "amount": "400g", "is_in_inventory": false },
    { "name": "Tomato", "amount": "4 pcs", "is_in_inventory": true }
  ],
  "steps": ["Slice the tomatoes and cook in a non-stick pan, then add the beef and cook through.", "Add fresh noodles and toss until cooked and hot."],
  "step_ingredients": [["Tomato", "Beef"], ["Noodles"]]
}
```

The backend requires JSON-only output, exact numeric quantities, serving extraction, inventory deduction, and explicit exclusion of words such as `people`, `persons`, `serving`, and `for 4` as ingredients. It strips fenced Markdown before parsing JSON and validates the response before returning it to Android.

`checklist-steps.js` extends that contract with complete ingredient amounts and ordered steps. Its parallel `step_ingredients` arrays must reference only declared names and collectively use every ingredient. Harmless Chinese annotations and conservative plurals are aligned; distinct foods and meaningful qualifiers are preserved. Ingredient display stock flags are derived from the authoritative existing missing/covered arrays, rather than rejecting a valid checklist for a redundant AI boolean. The prompt prohibits unlisted extras or substitutions. Generation normally takes one Gemini call; malformed or genuinely inconsistent output gets at most one corrective generation within the existing 45-second deadline. No correction starts after timeout/disconnect. Viewing steps makes no request and changes no data.

`AiChecklistScreen` adds a collapsible **View Recipe Steps** card directly beneath the dish summary, with readable numbered instructions in the existing cream/green theme and page scrolling. The ingredient checklist and both shopping/cooked callbacks remain intact. `GroceryChecklistResult` carries the steps; `SavedChecklist` retains them and complete ingredient metadata in the Room history snapshot. Room flow refreshes update the history list without overwriting active checkbox choices. Matching preference metadata is used only for one-time backfill of legacy rows. Old records safely have no instructions and invite regeneration. Expanding/collapsing steps has no inventory/shopping side effects.

In-stock rows use functional checkboxes, initially checked to retain the prior default consumption behavior. The `(Fully Covered)` and `In stock` labels remain on the right. `ChecklistInventorySelection.kt` supplies stable stock-selection keys, current-stock matching and conversion to the existing shopping-item input (without the coverage annotation). Unchecked stock and missing-item keys persist with the Room snapshot and survive rotation, reopening and direct history restoration.

**Add Selected to Shopping List** counts checked missing and in-stock items, including stock-only checklists. The target dialog keeps both Personal and Household options and shows a subtle note when the pending selection matches current inventory. This note does not block submission. The existing shopping repository handles quantities/merges and owner assignment. Ingredient quantities appear directly below names. A compact **Added** badge on the right is derived from all live, unpurchased Shopping List rows across owners and entry sources. This live status overlays both current and restored checklists; saved per-generation shopping markers are retained as history metadata but do not determine the badge or prevent selection. Checked rows can be added again; missing rows are unchecked after a successful submission and can be reselected, while in-stock rows keep their consumption selection. A busy flag prevents overlapping submissions, and each selected batch is merged in one Room transaction. Only checked in-stock rows are passed through the existing quantity calculation and FEFO deduction when marking a pending meal cooked. With no stock rows checked, the meal is marked cooked without consuming inventory. History completion respects the same saved selection.

### 6.4 Budget Tracker

Main files:

- `ui/budget/BudgetFragment.kt`
- `ui/budget/BudgetViewModel.kt`
- `data/repository/BudgetRepository.kt`
- `data/local/dao/BudgetDao.kt`

The Budget module stores a monthly limit, calculates spending from `InventoryLog` additions, displays remaining budget and category breakdowns, and sends an over-budget notification. The Shopping List uses the same repository data for its budget impact banner. Currency display is RM throughout the current UI and adapters.

### 6.5 Voice Input

Main files:

- `ui/main/MainActivity.kt`
- `ui/main/MainViewModel.kt`
- `ui/main/VoiceAudioCapture.kt`
- `ui/main/GeminiVoiceTranscription.kt`
- `ui/main/VoiceCommandConfirmationDialog.kt`
- `ui/main/VoiceInputPreferences.kt`
- `ui/main/VoiceRecognitionRequest.kt`
- `utils/VoiceParser.kt`
- `utils/VoiceCommandReview.kt`
- `utils/ShoppingVoiceMerge.kt`
- `utils/VoiceInventoryItemDraft.kt`
- `utils/VoiceLanguage.kt`
- `utils/MalayVoiceSupport.kt`
- `utils/VoiceRecognitionFailure.kt`
- `compose-pages/.../VoiceInventoryItemForm.kt`
- `backend/voice-transcription.js`

The default path records microphone audio with `AudioRecord` using the `MIC` source, mono 16-bit PCM at 16 kHz. Capture is bounded to 20 seconds and encoded as WAV in memory. A green level meter lets the user check the microphone signal; **Done speaking** ends capture and starts transcription. The client rejects recordings shorter than 0.4 seconds and clips with no microphone signal.

`GeminiVoiceTranscription` sends audio and optional item-name spelling hints to `POST /voice/transcribe`. Gemini transcribes the actual speech and automatically identifies English, Malay, Mandarin, or mixed/unknown speech. It does not execute grocery actions. Only confidently detected supported languages update `VoiceInputPreferences` (SharedPreferences `voice_input`, key `language`); the saved language is not a manual language lock. Uncertain detection preserves the previous preference and flags the review.

`VoiceParser.parseAll()` interprets one or multiple items. `VoiceCommandReview` validates every parsed action before enabling confirmation. Inventory supports additions and deductions; Shopping supports additions and rejects deductions. Recipe requests can open the existing AI checklist flow. The destination is captured when voice input starts, so subsequent navigation does not redirect a pending command.

The **Check voice command** dialog shows the transcript, actions, quantities, and destination. New inventory items expose editable details inside this review and save directly after confirmation. Only the explicit confirmation callback calls the existing `MainViewModel` mutation methods. Cancel, transcription failure, invalid commands, or merely displaying the review do not submit mutations. Recording/network work is cancelled when dismissed or backgrounded; stale results are ignored.

Shopping reviews also offer **Me only** and **Household**. The selection applies to every addition in the command, defaults to Household, and survives review recreation and editing. The review previews the combined quantity (for example, existing 1 egg + spoken 2 eggs = 3). Matching uses conservative normalized names, common singular/plural forms, and the existing multilingual aliases; Me and Household rows and purchased rows are kept separate. Whole item/package counts can merge, while incompatible measured labels such as grams or millilitres remain separate.

On explicit confirmation, `ShoppingListRepository.addVoiceItems()` calls `ShoppingListDao.addVoiceItems()` in one Room transaction. Each addition re-reads its matching row, combines the count, and preserves its price, category, and identity; unmatched additions create new rows. Overflow or another failure rolls back all additions in the command. Manual and AI checklist entry paths retain their existing behavior. When a voice addition adjusts an automatic low-stock row, that row is marked `addedBy = Voice` so automatic synchronization preserves the user's confirmed quantity; if the inventory source no longer needs restocking, the row is detached from that source rather than removed.

The existing Android `SpeechRecognizer` path and typed-command/retry fallback remain available. Native automatic language switching and installed speech models depend on the device/provider. `ui/quickadd/VoiceInputHandler.kt` is retained legacy support; the current Inventory and Shopping voice buttons enter through `MainActivity`.

### 6.6 Household Sharing

Main files:

- `ui/shopping/HouseholdInvitationDialog.kt`
- `ui/shopping/SharingLinkQrDialogStyle.kt`
- `ui/shopping/ShoppingShareDialogStyle.kt`
- `ui/auth/LocalAuthSession.kt`

The current project provides local household/member assignment, invite UI, share text, and QR link generation. There is no remote Firebase or cloud synchronization layer in the current source. Room Flow provides reactive local updates within the app.

### 6.7 Visual Food Recognition and Batch Review

Main files:

- `ui/quickadd/QuickAddBottomSheetDialog.kt`
- `ui/quickadd/IngredientScanBottomSheetDialog.kt`
- `ui/quickadd/IngredientScanViewModel.kt`
- `ui/quickadd/VisualFoodRecognition.kt`
- `compose-pages/.../BatchIngredientReviewSheet.kt`
- `utils/ExpiryEstimator.kt`
- `data/repository/GroceryRepository.kt`
- `data/local/dao/GroceryDao.kt`
- `backend/ingredient-scan.js`

The Add Item **Skip the typing** card opens a media-choice bottom sheet with **Take Photo** and **Choose from Gallery**. Camera output uses a temporary cache file exposed through FileProvider; the selected Uri is decoded, downsampled, corrected for EXIF orientation, and encoded as JPEG for the backend. `IngredientTextParser.kt` remains in the repository with its tests, but it is not the active visual recognition path.

`POST /ingredients/scan` uses Gemini multimodal vision to identify visible foods by appearance. Printed text is not required, and watermarks/filenames such as `Veg_1_Front` are excluded as food candidates. The response supplies specific item names, supported categories, and an optional visible whole-item count. The client accepts up to 20 detected candidates; an uncertain count defaults to 1.

Both single-food and multi-food photos go to **Batch Review Checklist**, bypassing single-item form autofill. Rows start selected and allow name/category edits, quantity controls, expiry selection, and deletion. **+ Add Item** adds a missed food manually (up to 50 total review rows). Expiry dates come from the local `ExpiryEstimator`, not the vision model; manual expiry selections are preserved. Drafts are retained through `SavedStateHandle`.

Only **Add All to Inventory** submits checked, valid rows. `GroceryRepository.insertBatch()` calls `GroceryDao.insertBatchWithLogs()` in one outer Room `@Transaction`, covering every inserted item and its ADD log. A failure rolls back the batch and preserves the review for retry. On success the sheet and Add Item form close, and Room Flow refreshes Inventory. The current photo batch destination is Inventory; Shopping voice additions use their separate flow. Photo batch items currently use price RM0.00 and threshold 2.

## 7. External Gemini API Flow

### 7.1 Android to Backend

```text
RecipeFragment
  -> RecipeViewModel.generateChecklist()
  -> RecipeRepository.generateChecklistAssistantResult()
  -> Retrofit POST /recipes/generate
  -> Node.js backend
```

The request contains the full user prompt and active, non-expired inventory. The Android repository filters inventory using:

```text
quantity > 0 AND expiryDate >= today
```

### 7.2 Backend to Gemini

File: `backend/server.js`

The backend:

1. Reads `GEMINI_API_KEY` from `.env`.
2. Converts Android inventory DTOs into `{ name, qty, unit }` objects.
3. Builds the strict checklist prompt.
4. Calls `GoogleGenAI.models.generateContent()`.
5. Requests `application/json` and a response schema.
6. Tries the configured model and retryable fallback models.
7. Extracts clean JSON from the response.
8. Validates dish name, serving count, required arrays, quantities and synchronized cooking ingredient references.
9. Returns checklist, complete ingredients and steps together to Android (45-second deadline, 6144 output tokens).

Endpoints:

```text
GET  /health
POST /recipes/generate
POST /ingredients/scan
POST /voice/transcribe
```

The endpoint name remains `/recipes/generate` for compatibility, but its current behavior is the AI checklist assistant.

The photo and voice routes have separate modules and schemas. Neither endpoint writes to Room:

| Endpoint | Input | Output | Request limit |
| --- | --- | --- | --- |
| `/recipes/generate` | Full prompt and usable inventory | Checklist, inventory deduction proposal, complete ingredients and cooking steps | 1 MB JSON |
| `/ingredients/scan` | `image_base64`, `mime_type` (client sends JPEG) | `items`: name, category, optional quantity | 6 MB JSON; client image maximum 4 MiB before Base64 |
| `/voice/transcribe` | `audio_base64`, `mime_type: audio/wav`, optional `item_names` | `transcript`, `language`, `speech_detected`, `language_confident` | 1 MB JSON; 0.4–20 seconds canonical PCM WAV |

Voice uses a 30-second backend deadline and validates audio format and structured output. Silence returns no transcript. Audio is held in memory, not saved by the app/backend, and is sent to Gemini for online processing. Supported language values are `en-US`, `ms-MY`, `zh-CN`, `mixed`, and `unknown`. The existing server-side model configuration and retryable model fallbacks are reused by all three AI routes.

### 7.3 Backend Configuration

Create `backend/.env` with values similar to:

```text
GEMINI_API_KEY=your_gemini_key
GEMINI_MODEL=gemini-3.5-flash-lite
PORT=8787
```

The actual key must never be committed to source control or placed in the Android project.

## 8. End-to-End Feature Workflows

### 8.1 Adding Inventory

```text
Inventory circular + button
  -> InventoryFeatureNavHost: inventory/add-grocery-item
  -> AddGroceryItemScreen (existing QuickAddBottomSheetDialog owner)
  -> user reviews fields and presses Save item
  -> InventoryViewModel.addNewItem()
  -> GroceryRepository.insert()
  -> GroceryDao.insertWithLog()
  -> Room inserts GroceryItem and ADD InventoryLog
  -> Flow emits new inventory
  -> Inventory UI redraws
  -> ShoppingListDao synchronizes low-stock entries
```

### 8.2 Generating a Checklist

```text
User enters dish request or presses Enter
  -> RecipeViewModel receives full prompt
  -> active inventory removes zero and expired rows
  -> Retrofit sends prompt + inventory to /recipes/generate
  -> backend sends strict prompt to Gemini
  -> Gemini returns structured JSON
  -> backend validates and normalizes JSON
  -> Android maps response to GroceryChecklistResult
  -> complete pending checklist snapshot is committed to Room and history is pruned atomically to ten entries
  -> UI displays missing items and matched stock
```

Successful searches are saved in local search history. The active history ID restores its Room snapshot on page recreation/reopening (otherwise the latest pending record is used). Tapping another history card restores that specific generation without another AI request.

### 8.3 Adding Checklist Items to Shopping

```text
User selects missing and/or in-stock checklist items
  -> target dialog warns if pending selected items are already in inventory
  -> chooses Me or Household (warning is non-blocking)
  -> RecipeViewModel calls ShoppingListRepository
  -> DAO re-reads pending rows for the selected owner in one transaction
  -> normalized names and compatible measurements accumulate for every food category
  -> incompatible measurements create a separate row without losing the previous amount
  -> selected missing rows are unchecked; shopping markers/selection keys are saved in the checklist
  -> all current/history ingredient badges react to the live shopping table
  -> purchased/deleted shopping rows stop contributing to the Added status
```

### 8.4 Purchasing a Shopping Item

```text
User checks a shopping item
  -> if price is missing, show purchase-price dialog
  -> user enters total price paid
  -> ShoppingListViewModel converts total price to unit price
  -> existing source inventory is restocked, or a new GroceryItem is inserted
  -> shopping entry is removed
  -> Room Flow updates Shopping List, Inventory, and Budget views
```

This is why marking an item purchased updates the Inventory screen automatically.

### 8.5 Marking a Meal as Cooked

```text
User presses Mark as Cooked
  -> RecipeViewModel loads inventory_deducted and saved unchecked-stock keys
  -> excludes unchecked stock rows from deduction
  -> finds matching inventory rows
  -> estimates a small pantry deduction for sauces/oils/salt/sugar
  -> uses exact parsed quantity for normal fresh ingredients
  -> consumes matching rows in expiry order
  -> updates checklist status to cooked
  -> clears the active pending checklist
  -> Room Flow hides zero-stock items
```

### 8.6 Shopping Budget Impact

```text
ShoppingListViewModel combines:
  active unchecked shopping items
  current monthly budget entry
  current-month inventory ADD logs

Then calculates:
  cartTotal = sum(quantityNeeded * estimatedUnitPrice)
  remainingBudget = totalBudget - spentAmount
```

The Shopping List displays a safe status when the cart is within the remaining budget and a warning when it exceeds the remaining amount.

### 8.7 Voice Command with Confirmation

```text
Inventory or Shopping voice button
  -> capture original destination and request RECORD_AUDIO if needed
  -> AudioRecord MIC capture with live level meter (up to 20 seconds)
  -> Done speaking / recording limit
  -> in-memory WAV -> Retrofit POST /voice/transcribe -> Gemini
  -> literal transcript and language metadata
  -> VoiceParser.parseAll() -> VoiceCommandReview validation
  -> Check voice command (editable details for new inventory items)
  -> for Shopping: choose Me only / Household and review existing + added quantity
  -> explicit Confirm / Save & confirm
  -> MainActivity.confirmVoiceCommand()
  -> existing Inventory or Shopping MainViewModel methods
  -> repositories -> Room -> reactive screen updates

Cancel / invalid review / recognition error -> no command submitted
```

### 8.8 Visual Food Batch Insertion

```text
Add Item -> Skip the typing
  -> media sheet: Take Photo / Choose from Gallery
  -> Uri -> oriented/downsampled JPEG
  -> VisualFoodRecognition -> POST /ingredients/scan -> Gemini vision
  -> IngredientScanViewModel creates unsaved BatchIngredientDraft rows
  -> local default expiry + quantity fallback
  -> Batch Review Checklist: select/edit/delete/add missing items
  -> explicit Add All to Inventory
  -> GroceryRepository.insertBatch()
  -> GroceryDao.insertBatchWithLogs() @Transaction
  -> all selected GroceryItems and ADD logs committed together
  -> dismiss review/form -> existing Room Flow refreshes Inventory
```

## 9. Main Algorithms

### 9.1 Inventory Filter Algorithm

```text
for each GroceryItem:
    discard if quantity <= 0

    if filter is ALL_IN_STOCK:
        keep if expiryDate is not expired
    if filter is EXPIRING_SOON:
        keep if expiryDate is inside warning window
    if filter is EXPIRED:
        keep if expiryDate is before today

filter by search text
group remaining rows by normalized item name
sort each group by earliest expiry date
sum quantities for the card
```

### 9.2 FEFO Quantity Deduction

```text
remainingToDeduct = requested quantity

for matching batch ordered by expiryDate ascending:
    amount = min(batch.quantity, remainingToDeduct)
    update batch quantity -= amount
    remainingToDeduct -= amount
    stop when remainingToDeduct == 0
```

When a quantity reaches zero, the row remains in the database but is excluded from the active inventory display until restocked.

### 9.3 Shopping List Aggregation

```text
begin one Room transaction for the selected ingredient batch
for each ingredient:
    read open shopping rows for the chosen Me/Household owner
    normalize its name with shared English/Malay/Mandarin aliases
    find a row with the same food identity and a compatible quantity unit
    if found: add the recipe amount to its existing quantity
    otherwise: insert a new pending shopping row
commit the batch (failure rolls back every ingredient)

Automatic low-stock rows extended by a confirmed AI addition retain that explicit
quantity during synchronization; restocking detaches the source link instead of
deleting the user's accumulated shopping plan.
```

### 9.4 Checklist Response Validation

```text
receive Gemini response
remove fenced JSON markers if present
extract the outer JSON object
parse JSON
require dish_name
require positive integer servings
require missing_checklist array
require inventory_deducted array
reject serving words used as ingredients
reject vague quantities
require complete ingredients and ordered steps
verify ingredient names/stock flags match the checklist
verify each step references only listed ingredients and every ingredient is used
return normalized result
```

### 9.5 Expiry and Notification Rules

- Expired means `expiryDate < today`.
- Expiring Soon uses the configurable warning-day preference, defaulting to 3 days.
- Low-stock alerts apply when quantity is at or below threshold.
- Zero-quantity items are not shown in active inventory.
- `InventoryAlertReceiver` restores scheduled checks after boot.
- `NotificationHelper` sends low-stock, expiry, and budget alerts.

### 9.6 Multilingual Command Review

```text
transcribe spoken English / Malay / Mandarin without translating
parse all item clauses, action words, numbers and supported units
normalize known grocery aliases through the local parser
reject a review if any command is unknown, has no item, or has invalid quantity
reject deductions when the captured destination is Shopping
load editable drafts for inventory additions with no existing match
enable confirmation only when the entire review and drafts are valid
re-validate the reviewed transcript on explicit confirmation
dispatch to the captured destination using existing ViewModel methods
```

This validation gates submission. Shopping voice additions use their own atomic merge transaction; Inventory voice actions retain their existing dispatch. The photo flow uses the separate atomic insertion path described in section 6.7.

## 10. Reactive Data Flow

The app is local-first:

```text
Room table change
  -> DAO Flow emits
  -> Repository exposes Flow
  -> ViewModel combines/filter state
  -> Fragment collects StateFlow
  -> adapter or UI elements update
```

There is no cloud database or real-time household backend in the current implementation. Household labels and assignments are stored locally. Gemini calls are network-dependent, while inventory, shopping list, budget, and saved checklist data remain available from Room offline.

## 11. UI Scrolling and Bottom-Safe Layout

The refactored pages use a shared cream (`#F9F8F3`), forest-green, terracotta (`#C85A32`), and pastel-green design. `PantryPalTheme`, `FeatureComponents`, and the existing XML drawable/style resources provide the visuals.

- Current Inventory, Shopping, Budget, and AI Checklist Fragments host Compose pages while retaining their existing bindings and state owners.
- Main feature pages use the shared `FeatureScrollPage` scroll container with `verticalScroll`, keyboard inset handling, and `100.dp` bottom padding.
- Add Item, Share & Connect, checklist history, and the new review/form sheets also use scrollable content with `100.dp` bottom padding.
- Existing lists embedded through `BoundLegacyView` retain their adapters; list height and disabled nested scrolling let the page own scrolling.
- AI Checklist pantry ingredients and recent searches retain their original `HorizontalScrollView` parents when bridged into Compose. Pantry chips can scroll horizontally within the page, with a visible horizontal scrollbar when the row overflows.
- Inventory's circular add button stays fixed above the bottom navigation. Add Item uses a full-screen destination that hides the bottom bar.
- XML scroll containers such as `scroll_inventory_page` and `scroll_shopping_page` remain in the retained layouts; they do not describe the entire current Compose page hierarchy.

RecyclerViews that size themselves to page content must remain measurable from their cards so the parent page can scroll through the complete list. Layout bridges call the existing listeners/callbacks rather than reimplementing business operations in Composables.

## 12. Build and Run

### Backend

```powershell
cd backend
npm install
npm start
```

The backend listens on `http://localhost:8787` by default.

### Android debug build

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Emulator microphone testing

Close an already running emulator before launching with host microphone access:

```powershell
.\tools\Start-VoiceEmulator.ps1
# Optional: choose a different installed AVD, or preview without launching.
.\tools\Start-VoiceEmulator.ps1 -Avd Medium_Phone -DryRun
```

The helper uses `-allow-host-audio -no-snapshot-load -no-snapshot-save`, defaults to `Medium_Phone` on port 5554, and does not wipe installed apps or Room data. It leaves an already running emulator alone. Allow Android microphone access, ensure the laptop microphone is unmuted, and check that ShelfWise's green input bar moves before tapping **Done speaking**.

### Automated verification

```powershell
# From the repository root
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest

# From backend/
node --test ingredient-scan.test.js voice-transcription.test.js checklist-steps.test.js
```

Unit tests cover expiry rules, command parsing (including Chinese/Malay and multiple items), review validation, inventory drafts, and WAV encoding. Instrumentation covers review/confirmation, cancellation, remembered language, Gemini integration through controlled responses, photo review, and batch insertion. `VoiceCommandTestRunner` installs an in-memory test database before application startup. Live Gemini and microphone diagnostic tests require explicit instrumentation opt-in arguments; they are not all exercised by the default suite.

## 13. Current Implementation Notes

- The Android endpoint is still named `/recipes/generate`, but the current backend response is a checklist response.
- Gemini API keys belong only in `backend/.env`.
- Inventory batches are represented as separate rows grouped by normalized name.
- Checklist history code is still present in the current source.
- Household sharing is currently local UI/state behavior, not server-backed synchronization.
- The app uses RM currency formatting in inventory, shopping, and budget displays.
- The ShelfWise app label does not rename the Android application ID, Room database, or retained source packages/classes.
- Voice transcription and visual recognition require the configured backend and a valid Gemini key. Typed/manual entry remains available when recognition fails.
- AI recognition produces review proposals. Voice confirmation and photo batch insertion are separate user-controlled submission paths.
- Live user testing verified an English Inventory command and a Mandarin Shopping command containing milk × 2 and chicken wing × 1. Malay has automated coverage but has not yet been verified with the user's own voice.


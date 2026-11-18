# Smart Grocery End-to-End Architecture and Workflow

This document explains the complete system workflow in a way that can be presented to both general audiences and technical professionals.

## 1. High-Level System Workflow

### 1.1 Simple Explanation

Smart Grocery is an Android app that helps users manage groceries, shopping needs, spending, voice commands, and AI-generated missing-item checklists.

At a high level, the app works like a smart household grocery notebook:

- **Inventory** is the list of items already at home.
- **Shopping List** is the list of items that need to be bought.
- **Budget Tracker** is the monthly spending monitor.
- **Voice Input** is a hands-free assistant for quick actions.
- **AI Checklist Assistant** analyzes a dish request and tells the user what ingredients are missing.
- **Local database** is the app's memory.
- **Backend server** is used when the app needs a Gemini-powered checklist response.

### 1.2 Step-by-Step App Workflow

1. **User opens the app**
   - The Android app starts from `MainActivity`.
   - `SmartGroceryApp` prepares shared repositories and the Room database.
   - The user can move between screens using bottom navigation.

2. **User performs an action**
   - Examples:
     - Add a grocery item.
     - Increase or decrease item quantity.
     - Add an item to the shopping list.
     - Mark a shopping item as purchased.
     - Set a monthly budget.
     - Speak a voice command.
     - Generate recipe recommendations.

3. **UI sends the action to a ViewModel**
   - Each screen has a ViewModel that controls its logic.
   - The ViewModel decides whether to update local storage, trigger a notification, open another screen, or call the backend.

4. **ViewModel calls the Repository layer**
   - Repositories act as the bridge between UI logic and data sources.
   - They hide Room database and network details from the UI.

5. **Data is stored or retrieved**
   - Most app data is stored locally in Room SQLite.
   - Inventory, budget, shopping list, inventory logs, and saved assistant results are handled from local app state.

6. **Screens update automatically**
   - The app uses Kotlin Flow and StateFlow.
   - When Room data changes, the updated data flows back to the UI.
   - This makes the app feel real-time without needing manual refresh.

7. **Notifications are sent when needed**
   - Low-stock items can trigger alerts.
   - Expiring-soon items can trigger alerts.
   - Over-budget spending can trigger alerts.

8. **AI recipe requests go through the backend**
   - The Android app sends inventory data to a local Node.js backend.
   - The backend calls Gemini.
   - The backend returns structured checklist JSON to the app.
   - The app displays the dish summary, missing items, and already-available stock.

### 1.3 End-to-End Flow Diagram

```text
User
  |
  v
Android Screen
  |
  v
ViewModel
  |
  v
Repository
  |
  +------------------> Room SQLite Database
  |                       |
  |                       v
  |                  Flow / StateFlow
  |                       |
  |                       v
  |                  UI refreshes
  |
  +------------------> Retrofit API Client
                          |
                          v
                    Node.js Backend
                          |
                          v
                      Gemini API
```

## 2. Overall Architecture

### 2.1 Architecture Pattern

The Android app follows an **MVVM-style architecture**:

- **View / Fragment**
  - Displays the UI.
  - Receives user interaction.
  - Observes state from ViewModel.

- **ViewModel**
  - Holds screen state.
  - Runs business logic.
  - Calls repositories.
  - Uses coroutines for background work.

- **Repository**
  - Provides a clean data API.
  - Talks to DAOs for local data.
  - Talks to Retrofit service for backend calls.

- **DAO**
  - Defines Room SQL queries.
  - Reads and writes local database tables.

- **Room SQLite Database**
  - Stores persistent app data locally.

- **Backend**
  - Receives AI recipe requests.
  - Calls Gemini securely.
  - Returns normalized checklist data.

### 2.2 Main Technology Stack

Android frontend:

- **Language:** Kotlin.
- **Build system:** Gradle Kotlin DSL.
- **UI:** XML layouts, Material Components, ViewBinding, RecyclerView, BottomSheetDialogFragment.
- **Architecture:** MVVM with ViewModel and Repository layers.
- **Local database:** Room SQLite.
- **Reactive updates:** Kotlin Flow and StateFlow.
- **Concurrency:** Kotlin coroutines and `viewModelScope`.
- **Network:** Retrofit 2 with Gson converter.
- **Image loading:** Glide.
- **Voice input:** Android `SpeechRecognizer`.
- **Notifications:** Android NotificationManager and NotificationCompat.
- **QR generation:** ZXing core.

Backend:

- **Runtime:** Node.js 20+.
- **Framework:** Express.
- **Configuration:** dotenv.
- **AI client:** Google GenAI SDK for Gemini.
- **Endpoint style:** JSON REST API.

## 3. Technical Breakdown by Feature

## 3.1 Inventory Management

### What It Does

Inventory Management stores groceries currently available at home and tracks quantity, price, category, expiry date, threshold, and purchase date.

### Main Files

- `InventoryFragment.kt`
- `InventoryViewModel.kt`
- `InventoryAdapter.kt`
- `QuickAddBottomSheetDialog.kt`
- `GroceryRepository.kt`
- `GroceryDao.kt`
- `GroceryItem.kt`
- `InventoryLog.kt`

### Tech Stack and Packages

- Kotlin.
- Room SQLite.
- Kotlin Flow / StateFlow.
- Android ViewModel.
- RecyclerView.
- Material Components.
- ViewBinding.
- Android notifications.

### Database Logic

Inventory uses two main tables:

| Table | Purpose |
| --- | --- |
| `grocery_items` | Stores the current inventory state. |
| `inventory_logs` | Stores add, deduct, voice deduct, and recipe deduct history. |

`GroceryItem` fields include:

- `id`
- `name`
- `quantity`
- `price`
- `category`
- `expiryDate`
- `threshold`
- `purchasedDate`

`InventoryLog` fields include:

- `itemId`
- `itemName`
- `category`
- `changeType`
- `amountChanged`
- `unitPriceAtChange`
- `timestamp`

### Frontend Mechanism

- The inventory screen observes `InventoryViewModel.inventoryItems`.
- The ViewModel combines:
  - the current search query
  - all grocery items from Room
- The UI automatically updates when either the search text or database data changes.

### Data Logic

When a user adds a grocery item:

```text
QuickAddBottomSheetDialog
  -> InventoryViewModel.addNewItem()
  -> GroceryRepository.insert()
  -> GroceryDao.insertWithLog()
  -> grocery_items insert
  -> inventory_logs ADD insert
  -> UI refreshes through Flow
```

When a user changes quantity:

```text
Inventory button
  -> InventoryViewModel.incrementQuantity() / decrementQuantity()
  -> GroceryRepository.updateQuantity()
  -> GroceryDao.updateQuantityWithLog()
  -> update grocery_items
  -> insert inventory_logs record
```

Important behavior:

- Quantity is prevented from going below zero.
- Low-stock notifications are sent when quantity reaches or falls below threshold.
- Expiring-soon notifications are sent for items expiring soon.

## 3.2 Shopping List

### What It Does

The Shopping List tracks items that need to be bought, including manual entries and automatically generated low-stock items.

### Main Files

- `ShoppingListFragment.kt`
- `ShoppingListViewModel.kt`
- `ShoppingListAdapter.kt`
- `ShoppingItemBottomSheetDialog.kt`
- `ShoppingListRepository.kt`
- `ShoppingListDao.kt`
- `ShoppingListItem.kt`

### Tech Stack and Packages

- Kotlin.
- Room SQLite.
- Kotlin Flow / StateFlow.
- ViewModel.
- RecyclerView.
- Material Components.
- BottomSheetDialogFragment.
- Android sharing intents.
- ClipboardManager.
- ZXing QR code generation.

### Database Logic

Shopping list uses:

| Table | Purpose |
| --- | --- |
| `shopping_list_items` | Stores manual and automatic shopping list entries. |

Important fields:

- `sourceItemId`: links an auto-created shopping item back to an inventory item.
- `itemName`
- `category`
- `quantityNeeded`
- `estimatedUnitPrice`
- `addedBy`
- `assignedTo`
- `isChecked`
- `createdAt`
- `updatedAt`

### Frontend Mechanism

- `ShoppingListFragment` displays shopping items.
- `ShoppingListViewModel` exposes:
  - all shopping list items
  - filtered items by assignee
  - expiry warning preference
- Users can filter by:
  - `All`
  - `Me`
  - `Household`

### Data Logic

Automatic low-stock sync:

```text
MainViewModel observes all inventory items
  -> when inventory changes, call ShoppingListRepository.syncLowStockItems()
  -> ShoppingListDao.syncLowStockItems()
  -> low-stock groceries are added or updated in shopping_list_items
  -> items no longer low-stock are removed from auto-generated shopping list
```

Manual shopping item:

```text
ShoppingItemBottomSheetDialog
  -> ShoppingListRepository.addManualItem()
  -> ShoppingListDao.insertManual()
  -> shopping_list_items insert
  -> UI refreshes through Flow
```

When a shopping item is checked as purchased:

```text
User checks item
  -> ShoppingListViewModel.setItemChecked()
  -> find matching inventory item
  -> increase inventory quantity
  -> delete shopping list item
```

## 3.3 Household Sharing

### What It Does

Household Sharing presents a shared shopping-list experience with household members, item assignment, invite codes, QR codes, and sharing options.

### Main Files

- `ShoppingListFragment.kt`
- `AuthBottomSheetDialog.kt`
- `ShoppingListViewModel.kt`
- `AppPreferencesRepository.kt`
- `ShoppingListItem.kt`

### Tech Stack and Packages

- Android SharedPreferences.
- Android Intent sharing.
- ClipboardManager.
- ZXing QRCodeWriter.
- Material Dialogs and Bottom Sheets.
- Room field `assignedTo` on `ShoppingListItem`.

### Database Logic

Household assignment is stored inside `shopping_list_items`:

- `assignedTo = "Me"`
- `assignedTo = "Household"`

The app filters shopping items based on this value.

### Frontend Mechanism

- Household members are displayed in the shopping screen.
- Users can assign shopping items to `Me` or `Household`.
- Users can share the shopping list using:
  - copied text
  - Android share sheet
  - QR code
  - generated join link
  - household invite code

### Current Implementation Note

This is a **local/prototype household sharing flow**, not a full cloud multi-user sync system.

Meaning:

- The UI supports household-style assignment and invitations.
- SharedPreferences stores sign-in state locally.
- Android intents and QR codes share information externally.
- There is no remote database that synchronizes household data across multiple devices yet.

## 3.4 Budget Tracker

### What It Does

The Budget Tracker calculates monthly grocery spending and compares it with a user-defined monthly limit.

### Main Files

- `BudgetFragment.kt`
- `BudgetViewModel.kt`
- `BudgetRepository.kt`
- `BudgetDao.kt`
- `BudgetEntry.kt`
- `InventoryLog.kt`
- `DateUtils.kt`

### Tech Stack and Packages

- Kotlin.
- Room SQLite.
- Kotlin Flow / StateFlow.
- ViewModel.
- Material UI.
- Android notifications.

### Database Logic

Budget uses:

| Table | Purpose |
| --- | --- |
| `budget_entries` | Stores monthly budget limits. |
| `inventory_logs` | Provides purchase history for spending calculation. |

Important point:

- Spending is calculated from `inventory_logs`.
- Only logs with `changeType = 'ADD'` count as spending.
- Deducting or consuming items does not reduce historical spending.

### Data Logic

```text
BudgetViewModel
  -> DateUtils gets current month start/end
  -> BudgetRepository gets budget limit
  -> BudgetRepository gets total ADD spending
  -> BudgetRepository gets category spending
  -> combine values into BudgetUiState
  -> BudgetFragment displays result
```

Budget UI state includes:

- `limit`
- `totalSpent`
- `remaining`
- `progressPercent`

Notification logic:

- If spending exceeds the limit, an over-budget notification is sent.
- A flag prevents repeated notification spam.

## 3.5 Voice Input

### What It Does

Voice Input lets users control inventory, shopping list, and recipes using spoken commands.

### Main Files

- `MainActivity.kt`
- `MainViewModel.kt`
- `VoiceParser.kt`
- `VoiceInputHandler.kt`

### Tech Stack and APIs

- Android `SpeechRecognizer`.
- Android `RecognizerIntent`.
- Kotlin custom parser.
- ViewModel.
- Kotlin coroutines.
- SharedFlow events.

### Supported Command Types

The parser maps speech into:

- `ADD`
- `DEDUCT`
- `RECIPE`
- `UNKNOWN`

Example commands:

```text
"add 3 apples"
"used 2 eggs"
"add bread to shopping list"
"suggest recipe with tomatoes"
```

### Data Logic

Inventory voice command:

```text
SpeechRecognizer result
  -> MainViewModel.processVoiceCommandOptions()
  -> VoiceParser.parseAll()
  -> ADD / DEDUCT / RECIPE
  -> update inventory or open recipe screen
```

Shopping-list voice command:

```text
SpeechRecognizer result
  -> MainViewModel.processShoppingVoiceCommandOptions()
  -> VoiceParser.parseAll()
  -> ADD command
  -> add manual shopping list item
```

Voice parser behavior:

- It supports quantity extraction.
- It supports multiple items in one sentence.
- It supports recipe-related phrases.
- It includes English and Chinese command handling in the current parser.

## 3.6 AI Checklist Assistant

### What It Does

The AI Checklist Assistant converts a dish request into a shopping checklist. It does not return cooking steps, food preparation guides, or recipe instructions.

### Main Files

Android:

- `RecipeFragment.kt`
- `RecipeViewModel.kt`
- `RecipeRepository.kt`
- `Recipe.kt`
- `AiRecipeClient.kt`
- `AiApiService.kt`
- `RecipeRequest.kt`
- `RecipeResponse.kt`

Backend:

- `backend/server.js`
- `backend/package.json`

### Tech Stack and Packages

Android:

- Kotlin.
- Retrofit.
- Gson converter.
- Room.
- Kotlin Flow / StateFlow.
- Glide.
- Material UI.

Backend:

- Node.js.
- Express.
- Google GenAI SDK.
- dotenv.
- CORS.

### Android Frontend Mechanism

Checklist generation starts in `RecipeViewModel.generateChecklist()`.

The app:

- Gets active inventory items where quantity is greater than zero.
- Sends the current inventory and user dish prompt to the backend through Retrofit.
- Receives a structured checklist response.
- Displays dish name, serving count, missing items, and already-available stock.
- Does not display recipe steps or cooking instructions.

### Backend Mechanism

Backend endpoint:

```text
POST /recipes/generate
```

Backend flow:

```text
Receive recipe request
  -> validate Gemini API key
  -> reject empty inventory with empty recipe list
  -> build recipe prompt
  -> call Gemini generateContent API
  -> parse structured JSON
  -> normalize checklist fields
  -> return checklist to Android
```

Health endpoint:

```text
GET /health
```

### Database Logic

The current checklist response is held in ViewModel UI state:

```text
ChecklistUiState
  -> dish_name
  -> servings
  -> missing_checklist
  -> inventory_deducted
```

### Checklist Logic

The assistant:

- extracts the dish and serving count from the user's prompt
- defaults to 2-3 servings if no serving count is provided
- estimates required ingredients and seasonings
- compares those needs with current inventory
- lists only missing or partially missing items with exact quantities and reasons
- acknowledges matched stock in `inventory_deducted`

## 4. Data and Network Flow

## 4.1 Local Storage Flow

Most data stays on the phone:

```text
User action
  -> Fragment / BottomSheet
  -> ViewModel
  -> Repository
  -> Room DAO
  -> Room SQLite table
```

Then the update flows back:

```text
Room SQLite table
  -> DAO Flow
  -> Repository Flow
  -> ViewModel StateFlow
  -> Fragment observes state
  -> UI refreshes
```

This is why the app can update instantly after actions like adding items or changing quantities.

## 4.2 Real-Time Sync Meaning in This Project

The current project has **real-time local sync**, not full cloud sync.

Real-time local sync means:

- Room emits updated data through Flow.
- ViewModels convert this into StateFlow.
- Screens update automatically while the app is running.

Example:

```text
User deducts eggs
  -> Room updates quantity
  -> Inventory screen updates
  -> Shopping list sync detects low stock
  -> Shopping list screen updates
  -> Budget remains based on purchase logs
```

Cloud or multi-device sync is not currently implemented as a real remote database feature.

## 4.3 External Backend Flow

Only the AI checklist assistant uses the backend.

```text
Android RecipeViewModel
  -> RecipeRepository
  -> Retrofit AiApiService
  -> POST /recipes/generate
  -> Node.js Express backend
  -> Gemini API
  -> normalized checklist JSON response
  -> Android RecipeRepository
  -> RecipeViewModel stores checklist result
  -> RecipeFragment displays missing checklist
```

Reason for backend:

- Protects the Gemini API key.
- Allows prompt and schema control on the server.
- Prevents cooking instructions by enforcing a checklist-only JSON schema.
- Normalizes AI responses before the app receives them.

## 4.4 Backend API Endpoints

### `GET /health`

Purpose:

- Confirms the backend is running.
- Returns the configured model.

Example response:

```json
{
  "ok": true,
  "provider": "gemini",
  "model": "gemini-3.6-flash",
  "mode": "checklist_assistant"
}
```

### `POST /recipes/generate`

Purpose:

- Generates a missing-item checklist from the requested dish and current inventory.

Simplified request:

```json
{
  "available_ingredients": [
    {
      "item_id": 1,
      "name": "Milk",
      "quantity": 1,
      "unit": "pcs",
      "is_expiring_soon": true
    }
  ],
  "user_prompt": "I want to cook Tomato Beef Noodles for 4 people"
}
```

Simplified response:

```json
{
  "dish_name": "Tomato Beef Noodles",
  "servings": 4,
  "missing_checklist": [
    {
      "item_name": "Beef Slices",
      "missing_quantity": "400g",
      "reason": "Need 500g for 4 servings, 100g available in stock",
      "category": "Meat"
    }
  ],
  "inventory_deducted": [
    {
      "item_name": "Tomato",
      "used_quantity": "4 pcs (Fully Covered)"
    }
  ]
}
```

## 5. Database Summary

| Table | Entity | Purpose |
| --- | --- | --- |
| `grocery_items` | `GroceryItem` | Stores current inventory. |
| `inventory_logs` | `InventoryLog` | Stores inventory change history. |
| `budget_entries` | `BudgetEntry` | Stores monthly budget limits. |
| `shopping_list_items` | `ShoppingListItem` | Stores manual and automatic shopping list entries. |
| `checklist_history` | `ChecklistHistoryItem` | Stores AI checklist history and pending/cooked/cancelled status. |

Current Room database:

```text
Database name: smart_grocery_database
Version: 7
```

Migration 6 -> 7 removes the unused `saved_recipes` table and its old records. Inventory, logs, budgets, shopping entries, and checklist history are preserved.

## 6. Frontend and Backend Responsibilities

### Android Frontend Responsibilities

- Display screens and collect user input.
- Store most app data locally.
- Reactively update UI from Room data.
- Trigger notifications.
- Parse voice commands.
- Build checklist requests from inventory and user prompt.
- Display AI-generated missing-item checklist results.

### Backend Responsibilities

- Receive AI checklist requests.
- Keep Gemini API key private.
- Build prompt and schema instructions.
- Call Gemini.
- Normalize checklist JSON.
- Return clean data to Android.

## 7. Presentation Summary

### For General Audience

Smart Grocery is a mobile app that acts like a smart kitchen assistant. It remembers what groceries are at home, warns when items are low or expiring, creates shopping lists, tracks spending, accepts voice commands, and uses Gemini to tell users what ingredients are missing for a requested dish.

### For Technical Audience

Smart Grocery is a Kotlin Android app using MVVM, Room SQLite, Repository pattern, Kotlin Flow/StateFlow, Retrofit, and a Node.js Express backend. Most features are local-first and reactive through Room Flow. The AI checklist assistant is the external backend flow, routed through Express to Gemini for secure API-key handling and structured checklist output.

### One-Sentence Architecture Pitch

Smart Grocery is a local-first Android grocery management system where Room stores the core data, ViewModels expose reactive screen state, repositories coordinate local and network operations, and a Node.js backend securely handles Gemini-powered missing-item checklist generation.

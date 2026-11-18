# Smart Grocery Management Application - Frontend Development Guide

## 1. Overview & Technology Stack

This document outlines the detailed architecture, implementation workflow, and component structure for the **Frontend** of the **Smart Application for Modern Grocery Management** (Android Application).

### Tech Stack
- **Language**: Kotlin (Primary) / Java
- **IDE**: Android Studio
- **UI Architecture**: Model-View-ViewModel (MVVM) + Jetpack ViewBinding / Jetpack Compose
- **Local Storage / Persistence**: SQLite via Android **Room Persistence Library**
- **Asynchronous / Concurrency**: Kotlin Coroutines & Flow
- **Voice Recognition**: Android SpeechRecognizer API / Google Voice Intent
- **Notifications**: Android AlarmManager / WorkManager + NotificationManager

---

## 2. Core Functional Requirements Alignment

All key features highlighted in the proposal statement are explicitly covered in this architecture:

1. **Real-time Inventory Updates**: Live data observation via Kotlin `StateFlow` / `LiveData` bound to UI components, ensuring immediate UI reflection when inventory changes.
2. **Automatic Quantity Deductions**: Dedicated deduction handlers triggered via voice input or quick-action controls.
3. **Simple Quick-Add Buttons**: Contextual fast-action buttons and bottom-sheet interfaces for 1-tap stock modifications.
4. **Voice Input Integration**: Built-in Android `SpeechRecognizer` pipeline connected to a local NLP parser for hands-free command processing.
5. **Budget Tracking**: Dedicated Budget Dashboard displaying real-time monthly ceiling, total expenditure, and category spending breakdown.

---

## 3. Architecture & Directory Structure

```text
com.xmum.smartgrocery/
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── dao/
│   │   │   ├── GroceryDao.kt
│   │   │   └── BudgetDao.kt
│   │   └── entity/
│   │       ├── GroceryItem.kt
│   │       └── BudgetEntry.kt
│   └── repository/
│       ├── GroceryRepository.kt
│       └── BudgetRepository.kt
├── ui/
│   ├── main/
│   │   ├── MainActivity.kt
│   │   └── MainViewModel.kt
│   ├── inventory/
│   │   ├── InventoryFragment.kt
│   │   ├── InventoryAdapter.kt
│   │   └── InventoryViewModel.kt
│   ├── quickadd/
│   │   ├── QuickAddBottomSheetDialog.kt
│   │   └── VoiceInputHandler.kt
│   ├── budget/
│   │   ├── BudgetFragment.kt
│   │   └── BudgetViewModel.kt
│   └── common/
│       └── NotificationHelper.kt
├── utils/
│   ├── VoiceParser.kt
│   └── DateUtils.kt
└── SmartGroceryApp.kt
```

---

## 4. Detailed Step-by-Step Implementation Flow

### Step 1: Project Setup & Dependency Configuration
- Initialize Android Studio project targeting **Min SDK 24** and **Target SDK 34/35**.
- Add necessary dependencies in `build.gradle.kts`:
  - Room DB (`androidx.room:room-runtime`, `androidx.room:room-ktx`)
  - Lifecycle & ViewModel (`androidx.lifecycle:lifecycle-viewmodel-ktx`)
  - Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-android`)
  - Navigation Component (`androidx.navigation:navigation-fragment-ktx`)

### Step 2: Real-Time UI Implementation (Real-time Updates & Quick-Add)
- **`fragment_inventory.xml`**:
  - `RecyclerView` observing `StateFlow<List<GroceryItem>>` for zero-latency UI updates.
  - Quick increment (`+`) and decrement (`-`) buttons on every item card for 1-click quantity adjustments.
  - Visual status chips showing Low-Stock and Expiring Soon flags.

### Step 3: Voice Input & Automatic Deduction Engine
- **`VoiceInputHandler.kt`**:
  - Captures voice commands via `SpeechRecognizer`.
- **`VoiceParser.kt`**:
  - Extracts actions and quantities (e.g., *"used 2 eggs"* -> Action: `DEDUCT`, Target: `eggs`, Qty: `2`).
  - Calls `ViewModel.applyVoiceDeduction(itemName, quantity)` to perform automated database deductions and UI refreshes.

### Step 4: Budget Tracking View
- **`fragment_budget.xml`**:
  - Monthly budget limit input and visual progress indicator (`Total Spent / Limit`).
  - Real-time updates whenever new grocery purchases with item prices are recorded.

---

## 5. Build & Verification Checklist

- [x] **Real-time Inventory Updates**: Implemented via Coroutines Flow.
- [x] **Automatic Quantity Deductions**: Voice NLP parser & single-click deduction triggers.
- [x] **Quick-Add Buttons**: Adapter-level quick action handlers.
- [x] **Voice Input**: Integrated native speech intent.
- [x] **Budget Tracking**: Budget DAO + UI summary cards.

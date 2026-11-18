# Requirement Existence Check

Checked against the attached requirement list and current project source.

## Summary

The project implements inventory, quick-add/voice, smart shopping list, basic shared household list views, budget, Room persistence, MVVM, preferences, and notification requirements. The current project also includes an AI recipe backend, which does not align with the attached requirement that the system should avoid cloud servers and external APIs.

## Functional Modules

| Requirement | Current status | Evidence |
| --- | --- | --- |
| Real-time inventory catalog and stock browsing | Exists | `InventoryFragment`, `InventoryAdapter`, `GroceryItem`, and `GroceryDao.getAllItems()` display and stream inventory records. |
| Item categories | Exists | `GroceryItem.category`; quick-add category selector. |
| Manual stock increments/decrements | Exists | `InventoryFragment` wires adapter actions to `InventoryViewModel.incrementQuantity()` and `decrementQuantity()`. |
| Low-stock alerts | Exists | `GroceryItem.threshold`, `GroceryItem.isLowStock`, `GroceryDao.getLowStockItems()`, and `NotificationHelper.sendLowStockNotification()`. |
| Expiry alerts | Exists | `GroceryItem.expiryDate`, `GroceryItem.isExpiringSoon`, and `InventoryViewModel` sends expiring-soon notifications. |
| Usage history logging | Partially exists | `inventory_logs` records add/deduct events through DAO transactions, but there is no visible usage-history screen. |
| Home screen quick-add | Exists | Inventory screen FAB opens `QuickAddBottomSheetDialog`; voice ADD can also open the quick-add sheet prefilled. |
| Voice-activated commands / NLP parsing | Exists | `MainActivity` uses Android speech recognition; `VoiceParser` parses add, deduct, and recipe intents. |
| Voice adjusts inventory database quantities | Exists | `MainViewModel` routes parsed ADD/DEDUCT commands to `GroceryRepository`. |
| Automated shopping-list generation | Exists | `ShoppingListItem`, `ShoppingListDao.syncLowStockItems()`, `ShoppingListRepository`, `ShoppingListViewModel`, `ShoppingListFragment`, and Shopping bottom navigation are implemented. |
| Shared shopping-list / multi-user view | Exists | Shopping items store `addedBy` and `assignedTo`; the UI has member filters for All/Me/Housemate/Household, per-item assignment, and Android share/export. |
| Monthly grocery budget limit | Exists | `BudgetEntry`, `BudgetDao`, `BudgetViewModel.updateBudgetLimit()`, and budget UI. |
| Spending tracker and analytics | Exists | Spending is calculated from `inventory_logs`; category totals and chart UI exist. |
| Local SQLite/Room persistence | Exists | `AppDatabase` uses Room with grocery, budget, inventory log, and saved recipe entities. |
| Store shopping lists locally | Exists | `shopping_list_items` is a Room table managed by `ShoppingListItem` and `ShoppingListDao`. |
| Custom minimum stock thresholds | Exists | Quick-add/edit supports threshold input and stores it on each grocery item. |
| Custom expiration warning days | Exists | `AppPreferencesRepository` stores configurable expiry warning days; inventory, recipe, and shopping screens read the setting. |

## Frontend Architecture

| Requirement | Current status | Evidence |
| --- | --- | --- |
| Native Android using Kotlin/Java | Exists | Kotlin Android app using Gradle Kotlin DSL. |
| MVVM architecture | Exists | UI packages contain Fragments; ViewModels call repositories; repositories call Room DAOs/network service. |
| XML layouts or Compose | Exists | XML layouts with ViewBinding are used. |
| Room/SQLite database | Exists | `AppDatabase` and Room dependencies are present. |
| Android native SpeechRecognizer | Exists | `MainActivity` launches `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`. |
| Shopping list UI package | Exists | Shopping list UI is implemented in `app/src/main/java/com/xmum/smartgrocery/ui/shopping`. |

## Backend / Scope Alignment

| Requirement | Current status | Evidence |
| --- | --- | --- |
| Local on-device backend only | Partially aligned | Inventory and budget are local Room data. |
| No cloud server or external API | Not aligned | Project has `backend/server.js`, Android `INTERNET` permission, Retrofit `AI_BASE_URL`, and `/recipes/generate`. |
| Shopping list table in SQLite | Exists | `AppDatabase` includes `ShoppingListItem` and migrates `shopping_list_items` through Room version 4. |

## Verification

Ran:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

Result: build and unit tests passed. Gradle network access was required once to download the wrapper distribution.

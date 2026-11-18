package com.xmum.smartgrocery

import android.app.Application
import com.xmum.smartgrocery.data.network.AiRecipeClient
import com.xmum.smartgrocery.data.local.AppDatabase
import com.xmum.smartgrocery.data.repository.AppPreferencesRepository
import com.xmum.smartgrocery.data.repository.BudgetRepository
import com.xmum.smartgrocery.data.repository.GroceryRepository
import com.xmum.smartgrocery.data.repository.RecipeRepository
import com.xmum.smartgrocery.data.repository.ShoppingListRepository
import com.xmum.smartgrocery.ui.common.InventoryAlertReceiver

class SmartGroceryApp : Application() {

    // Using lazy so the database and repositories are only created when they're needed
    // rather than at startup
    val database by lazy { AppDatabase.getDatabase(this) }
    val sessionManager by lazy { com.xmum.smartgrocery.data.session.SessionManager(database) }
    private val aiApiService by lazy {
        BuildConfig.AI_BASE_URL
            .takeIf { it.isNotBlank() }
            ?.let { AiRecipeClient.create(it) }
    }
    val groceryRepository by lazy { GroceryRepository(database.groceryDao()) }
    val budgetRepository by lazy { BudgetRepository(database.budgetDao()) }
    val recipeRepository by lazy { RecipeRepository(aiApiService) }
    val shoppingListRepository by lazy { ShoppingListRepository(database.shoppingListDao(), sessionManager) }
    val preferencesRepository by lazy { AppPreferencesRepository(this) }
    val checklistHistoryRepository by lazy { com.xmum.smartgrocery.data.repository.ChecklistHistoryRepository(database.checklistHistoryDao()) }

    override fun onCreate() {
        super.onCreate()
        com.xmum.smartgrocery.data.sharing.SharingRuntime.install(this, database)
        com.xmum.smartgrocery.ui.common.RestockMonitor.install(this, database)
        InventoryAlertReceiver.schedule(this)
    }
}

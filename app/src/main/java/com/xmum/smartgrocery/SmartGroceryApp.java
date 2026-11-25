package com.xmum.smartgrocery;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xmum.smartgrocery.data.local.AppDatabase;
import com.xmum.smartgrocery.data.network.AiApiService;
import com.xmum.smartgrocery.data.network.AiRecipeClient;
import com.xmum.smartgrocery.data.repository.AppPreferencesRepository;
import com.xmum.smartgrocery.data.repository.BudgetRepository;
import com.xmum.smartgrocery.data.repository.ChecklistHistoryRepository;
import com.xmum.smartgrocery.data.repository.GroceryRepository;
import com.xmum.smartgrocery.data.repository.RecipeRepository;
import com.xmum.smartgrocery.data.repository.ShoppingListRepository;
import com.xmum.smartgrocery.data.session.SessionManager;
import com.xmum.smartgrocery.data.sharing.SharingRuntime;
import com.xmum.smartgrocery.ui.common.InventoryAlertReceiver;
import com.xmum.smartgrocery.ui.common.RestockMonitor;

import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.text.StringsKt;

public final class SmartGroceryApp extends Application {
    private final Lazy<AppDatabase> database = LazyKt.lazy(() -> AppDatabase.getDatabase(this));
    private final Lazy<SessionManager> sessionManager = LazyKt.lazy(() -> new SessionManager(getDatabase()));
    private final Lazy<AiApiService> aiApiService = LazyKt.lazy(this::createAiApiService);
    private final Lazy<GroceryRepository> groceryRepository =
            LazyKt.lazy(() -> new GroceryRepository(getDatabase().groceryDao()));
    private final Lazy<BudgetRepository> budgetRepository =
            LazyKt.lazy(() -> new BudgetRepository(getDatabase().budgetDao()));
    private final Lazy<RecipeRepository> recipeRepository =
            LazyKt.lazy(() -> new RecipeRepository(aiApiService.getValue()));
    private final Lazy<ShoppingListRepository> shoppingListRepository =
            LazyKt.lazy(() -> new ShoppingListRepository(getDatabase().shoppingListDao(), getSessionManager()));
    private final Lazy<AppPreferencesRepository> preferencesRepository =
            LazyKt.lazy(() -> new AppPreferencesRepository(this));
    private final Lazy<ChecklistHistoryRepository> checklistHistoryRepository =
            LazyKt.lazy(() -> new ChecklistHistoryRepository(getDatabase().checklistHistoryDao()));

    @Override
    public void onCreate() {
        super.onCreate();
        SharingRuntime.install(this, getDatabase());
        RestockMonitor.install(this, getDatabase());
        InventoryAlertReceiver.schedule(this);
    }

    @Nullable
    private AiApiService createAiApiService() {
        String url = BuildConfig.AI_BASE_URL;
        return StringsKt.isBlank(url) ? null : AiRecipeClient.create(url);
    }

    @NonNull
    public AppDatabase getDatabase() { return database.getValue(); }

    @NonNull
    public SessionManager getSessionManager() { return sessionManager.getValue(); }

    @NonNull
    public GroceryRepository getGroceryRepository() { return groceryRepository.getValue(); }

    @NonNull
    public BudgetRepository getBudgetRepository() { return budgetRepository.getValue(); }

    @NonNull
    public RecipeRepository getRecipeRepository() { return recipeRepository.getValue(); }

    @NonNull
    public ShoppingListRepository getShoppingListRepository() { return shoppingListRepository.getValue(); }

    @NonNull
    public AppPreferencesRepository getPreferencesRepository() { return preferencesRepository.getValue(); }

    @NonNull
    public ChecklistHistoryRepository getChecklistHistoryRepository() { return checklistHistoryRepository.getValue(); }
}

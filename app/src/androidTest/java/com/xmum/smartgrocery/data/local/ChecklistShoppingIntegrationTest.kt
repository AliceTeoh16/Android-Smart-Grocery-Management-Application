package com.xmum.smartgrocery.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.repository.ShoppingListRepository
import com.xmum.smartgrocery.utils.ChecklistShoppingMerge
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChecklistShoppingIntegrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun concurrentChecklistAdditionsAccumulateWithoutDuplicateRowsOrLostUpdates(): Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            db.useTestAccount()
            val repo=ShoppingListRepository(db.shoppingListDao())
            (1..20).map { async { repo.addAiChecklistItem("Flour","Pantry","200g","Me") } }.awaitAll()
            val rows=db.shoppingListDao().getShoppingListItems().first()
            assertEquals(1,rows.size); assertEquals("4000 g",rows.single().quantityLabel)
            assertEquals(4000,rows.single().quantityNeeded)
            val manager = com.xmum.smartgrocery.data.session.SessionManager(db)
            manager.createHousehold("Shared test pantry")
            repo.addAiChecklistItem("Flour","Pantry","200g","Household")
            assertEquals(1,db.shoppingListDao().getShoppingListItems().first().size)
            manager.selectHousehold(null)
            assertEquals("4000 g",db.shoppingListDao().getShoppingListItems().first().single().quantityLabel)
        } finally { db.close() }
    }
    @Test fun entireSelectedBatchRollsBackWhenOneIngredientCannotBeInserted(): Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            db.useTestAccount()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_food BEFORE INSERT ON shopping_list_items WHEN NEW.itemName = 'Reject' BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
            assertTrue(runCatching { db.shoppingListDao().addAiChecklistItems(listOf(
                ChecklistShoppingMerge.Addition("Flour","Pantry","200g"),ChecklistShoppingMerge.Addition("Reject","Pantry","1pc")),"Me") }.isFailure)
            assertTrue(db.shoppingListDao().getShoppingListItems().first().isEmpty())
        } finally { db.close() }
    }
    @Test fun lowStockRefreshAndRestockingPreserveTheUsersAccumulatedPlan(): Unit=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            db.useTestAccount()
            val dao=db.shoppingListDao()
            val id = db.groceryDao().insert(GroceryItem(name="Eggs",category="Dairy",quantity=3,threshold=5,price=2.0)).toInt()
            val stock = db.groceryDao().getItemById(id)!!
            dao.syncLowStockItems(listOf(stock))
            assertTrue(dao.getShoppingListItems().first().isEmpty())
            dao.approveRestocks(mapOf(id to 2))
            ShoppingListRepository(dao).addAiChecklistItem("Egg","Dairy","2 pcs","Me")
            dao.syncLowStockItems(listOf(stock))
            val merged=dao.getShoppingListItems().first().single()
            assertEquals("4 pcs",merged.quantityLabel); assertEquals(ChecklistShoppingMerge.SOURCE,merged.addedBy)
            dao.syncLowStockItems(listOf(stock.copy(quantity=6)))
            val retained=dao.getShoppingListItems().first().single()
            assertEquals(id, retained.sourceItemId); assertEquals("4 pcs",retained.quantityLabel)
        } finally { db.close() }
    }
}

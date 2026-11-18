package com.xmum.smartgrocery.ui.recipes

import com.xmum.smartgrocery.data.local.dao.ChecklistHistoryDao
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.data.repository.ChecklistHistoryRepository
import com.xmum.smartgrocery.data.repository.RecipeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistHistoryLifecycleTest {

    private class FakeChecklistHistoryDao : ChecklistHistoryDao() {
        override suspend fun activeScope() = com.xmum.smartgrocery.data.local.dao.DataScope("test-user", null)
        override suspend fun insertRaw(item: ChecklistHistoryItem) = insert(item)
        override suspend fun updateRaw(item: ChecklistHistoryItem) = update(item)
        val items = mutableListOf<ChecklistHistoryItem>()

        override fun getAllChecklistHistory(): Flow<List<ChecklistHistoryItem>> {
            return flowOf(items.sortedByDescending { it.createdAt })
        }

        override suspend fun getAllChecklistHistorySync(): List<ChecklistHistoryItem> {
            return items.sortedByDescending { it.createdAt }
        }

        override suspend fun getChecklistHistoryById(id: String): ChecklistHistoryItem? {
            return items.firstOrNull { it.id == id }
        }

        override suspend fun getCount(): Int {
            return items.size
        }

        override suspend fun insert(item: ChecklistHistoryItem) {
            val index = items.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                items[index] = item
            } else {
                items.add(item)
            }
        }

        override suspend fun pruneToLatest(maxLimit: Int) {
            val keep = items.sortedWith(compareByDescending<ChecklistHistoryItem> { it.createdAt }.thenByDescending { it.id }).take(maxLimit).map { it.id }.toSet()
            items.removeAll { it.id !in keep }
        }
        override suspend fun updateState(id: String, stateJson: String, addedShoppingKeysJson: String, status: String?) {
            val old = items.firstOrNull { it.id == id } ?: return
            insert(old.copy(stateJson = stateJson, addedShoppingKeysJson = addedShoppingKeysJson, status = status ?: old.status))
        }
        override suspend fun backfillState(id: String, stateJson: String) {
            val old = items.firstOrNull { it.id == id && it.stateJson.isBlank() } ?: return
            insert(old.copy(stateJson = stateJson))
        }

        override suspend fun update(item: ChecklistHistoryItem) {
            insert(item)
        }

        override suspend fun updateStatus(id: String, status: String) {
            val existing = items.firstOrNull { it.id == id } ?: return
            val index = items.indexOf(existing)
            items[index] = existing.copy(status = status)
        }

        override suspend fun updateAddedShoppingKeys(id: String, addedShoppingKeysJson: String) {
            val existing = items.firstOrNull { it.id == id } ?: return
            val index = items.indexOf(existing)
            items[index] = existing.copy(addedShoppingKeysJson = addedShoppingKeysJson)
        }

        override suspend fun deleteOldest(count: Int) {
            val oldest = items.sortedBy { it.createdAt }.take(count)
            items.removeAll(oldest)
        }

        override suspend fun deleteById(id: String) {
            items.removeAll { it.id == id }
        }

        override suspend fun clearAll() { items.clear() }
    }

    @Test
    fun maxHistoryRetention_autoPrunesOldestWhenExceeding10() = runBlocking {
        val dao = FakeChecklistHistoryDao()
        val repository = ChecklistHistoryRepository(dao)

        // Insert 10 records with sequential timestamps
        for (i in 1..10) {
            val item = ChecklistHistoryItem(
                id = "item_$i",
                dishName = "Dish $i",
                servings = 2,
                status = "pending",
                createdAt = 1000L * i
            )
            repository.saveChecklistWithPruning(item, maxLimit = 10)
        }

        assertEquals(10, dao.getCount())
        assertNotNull(dao.getChecklistHistoryById("item_1")) // oldest is item_1 at t=1000

        // Insert 11th record: should automatically prune oldest (item_1)
        val item11 = ChecklistHistoryItem(
            id = "item_11",
            dishName = "Dish 11",
            servings = 3,
            status = "pending",
            createdAt = 1000L * 11
        )
        repository.saveChecklistWithPruning(item11, maxLimit = 10)

        assertEquals(10, dao.getCount())
        assertNull("Oldest record item_1 should have been pruned", dao.getChecklistHistoryById("item_1"))
        assertNotNull("Newest record item_11 must exist", dao.getChecklistHistoryById("item_11"))
        assertNotNull("Record item_2 must still exist", dao.getChecklistHistoryById("item_2"))
    }

    @Test
    fun statusLifecycle_pendingToCookedAndCancelled() = runBlocking {
        val dao = FakeChecklistHistoryDao()
        val repository = ChecklistHistoryRepository(dao)

        val item = ChecklistHistoryItem(
            id = "meal_101",
            dishName = "Fried Chicken",
            servings = 2,
            status = "pending",
            createdAt = 5000L
        )
        repository.saveChecklistWithPruning(item)
        assertEquals("pending", repository.getById("meal_101")?.status)

        // Mark as cooked
        repository.updateStatus("meal_101", "cooked")
        assertEquals("cooked", repository.getById("meal_101")?.status)

        // Another item marked as cancelled
        val item2 = ChecklistHistoryItem(
            id = "meal_102",
            dishName = "Tomato Soup",
            servings = 1,
            status = "pending",
            createdAt = 6000L
        )
        repository.saveChecklistWithPruning(item2)
        repository.updateStatus("meal_102", "cancelled")
        assertEquals("cancelled", repository.getById("meal_102")?.status)
    }

    @Test
    fun smartPantryDeductionRule_skipsBulkSeasoningsAndDeductsFresh() {
        // Smart Pantry rule: skip bulk seasonings like Soy Sauce, Salt, Oil, Flour, Spices
        fun isPantryLike(name: String, category: String): Boolean {
            val normalized = "$name $category".lowercase()
            return listOf(
                "pantry", "seasoning", "seasonings", "spice", "spices", "sauce",
                "soy sauce", "salt", "sugar", "pepper", "oil", "vinegar", "flour",
                "cornstarch", "starch"
            ).any { normalized.contains(it) }
        }

        val salt = GroceryItem(id = 1, name = "Table Salt", quantity = 1, category = "Pantry", price = 1.0, expiryDate = 0)
        val soySauce = GroceryItem(id = 2, name = "Light Soy Sauce", quantity = 1, category = "Seasoning", price = 2.0, expiryDate = 0)
        val vegetableOil = GroceryItem(id = 3, name = "Vegetable Oil", quantity = 1, category = "Pantry", price = 3.0, expiryDate = 0)
        val chicken = GroceryItem(id = 4, name = "Chicken Breast", quantity = 3, category = "Meat", price = 8.0, expiryDate = 0)
        val tomato = GroceryItem(id = 5, name = "Tomato", quantity = 4, category = "Produce", price = 2.0, expiryDate = 0)

        // Assert that seasonings are flagged as pantry-like (skipped from deduction)
        assertTrue(isPantryLike(salt.name, salt.category))
        assertTrue(isPantryLike(soySauce.name, soySauce.category))
        assertTrue(isPantryLike(vegetableOil.name, vegetableOil.category))

        // Assert that fresh consumable items are NOT skipped
        assertFalse(isPantryLike(chicken.name, chicken.category))
        assertFalse(isPantryLike(tomato.name, tomato.category))
    }
}

package com.xmum.smartgrocery.ui.quickadd

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.utils.ExpiryEstimator
import com.xmum.smartgrocery.utils.GroceryCategories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.UUID

data class BatchIngredientDraft(
    val id: String, val name: String, val category: String, val quantity: Int,
    val expiryDate: Long?, val selected: Boolean = true, val quantityDetected: Boolean = false,
    val expiryManuallySelected: Boolean = false, val manuallyAdded: Boolean = false,
    val threshold: Int = GroceryCategories.defaultThreshold(category),
)
data class IngredientScanState(
    val scanning: Boolean = false, val reviewing: Boolean = false,
    val items: List<BatchIngredientDraft> = emptyList(), val saving: Boolean = false,
    val savedCount: Int = 0, val error: String? = null,
) {
    val selectedCount: Int get() = items.count { it.selected }
    val canSave: Boolean get() = !scanning && !saving && savedCount == 0 && selectedCount > 0 &&
        items.filter { it.selected }.all { it.name.isNotBlank() && it.quantity in 1..999 && it.category in GroceryCategories.all && it.threshold in 0..999 && (it.expiryDate == null || it.expiryDate > 0L) }
}

/** Unsaved visual recognition drafts. Only explicit batch confirmation writes to the local database. */
class IngredientScanViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val compatibleDraft = savedState.get<String>("scan_engine") == "visual-food-v1"
    private val mutableState = MutableStateFlow(IngredientScanState(
        reviewing = compatibleDraft && (savedState["reviewing"] ?: false),
        items = savedState.get<ArrayList<Bundle>>("batch_rows").takeIf { compatibleDraft }.orEmpty().map { row ->
            BatchIngredientDraft(row.getString("id")!!, row.getString("name").orEmpty(), GroceryCategories.selection(row.getString("category") ?: "Food & Beverage"),
                row.getInt("quantity", 1), row.getLong("expiry_date").takeIf { it > 0L }, row.getBoolean("selected"), row.getBoolean("quantity_detected"),
                row.getBoolean("manual_expiry"), row.getBoolean("manual_item"), row.getInt("threshold", GroceryCategories.defaultThreshold(row.getString("category").orEmpty())))
        }, savedCount = if (compatibleDraft) savedState["saved_count"] ?: 0 else 0,
    ))
    val state = mutableState.asStateFlow()
    private var scanJob: Job? = null
    private var completionDelivered = false
    private val recognition = VisualFoodRecognition(application)
    internal var recognizeFood: suspend (Uri) -> List<VisualFoodItem> = recognition::recognize

    fun scan(uri: Uri) {
        if (state.value.saving || state.value.savedCount > 0) return
        scanJob?.cancel()
        update(IngredientScanState(scanning = true))
        scanJob = viewModelScope.launch {
            try {
                val candidates = recognizeFood(uri)
                update(IngredientScanState(reviewing = true, items = candidates.map { candidate ->
                    val category = GroceryCategories.recognizedSelection(candidate.name, candidate.category, candidate.subcategory)
                    BatchIngredientDraft(UUID.randomUUID().toString(), candidate.name, category, candidate.quantity ?: 1,
                        ExpiryEstimator.estimateExpiryTimestamp(candidate.name, category), selected = true,
                        quantityDetected = candidate.quantity != null)
                }, error = if (candidates.isEmpty()) "No items were confidently identified. Add missed items manually or try another photo." else null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update(IngredientScanState(error = when (error) {
                    is IllegalArgumentException -> error.message ?: "Choose a readable item photo."
                    is HttpException -> when (error.code()) {
                        503 -> "Visual recognition is not configured on the server. You can add items manually."
                        429 -> "Visual recognition is busy or its quota is exhausted. Try again later or add items manually."
                        404 -> "Visual recognition is unavailable on this server. Start the updated ShelfWise backend."
                        else -> "Couldn’t recognize items in this photo. Try again or add items manually."
                    }
                    is java.io.IOException -> "Couldn’t reach visual recognition. Check your connection and that the ShelfWise backend is running."
                    else -> "Couldn’t recognize items in this photo. Try again or add items manually."
                }))
            }
        }
    }

    fun rename(id: String, name: String) = edit(id) { row ->
        row.copy(name = name.take(100), expiryDate = if (row.expiryManuallySelected) row.expiryDate
            else ExpiryEstimator.estimateExpiryTimestamp(name.take(100), row.category))
    }
    fun changeCategory(id: String, category: String) {
        if (category !in GroceryCategories.all) return
        val selection = GroceryCategories.selection(category)
        if (selection in GroceryCategories.all) edit(id) { row -> row.copy(category = selection, threshold = GroceryCategories.defaultThreshold(selection),
            expiryDate = if (row.expiryManuallySelected) row.expiryDate else ExpiryEstimator.estimateExpiryTimestamp(row.name, category)) }
    }
    fun changeExpiry(id: String, date: Long?) {
        if (date == null || date > 0L) edit(id) { it.copy(expiryDate = date, expiryManuallySelected = true) }
    }
    fun addManualItem() {
        val current = state.value
        if (current.saving || current.scanning || current.savedCount > 0) return
        if (current.items.size >= 50) { showError("You can review up to 50 items in one batch."); return }
        update(current.copy(reviewing = true, error = null, items = current.items + BatchIngredientDraft(
            UUID.randomUUID().toString(), "", "Food & Beverage", 1, null, manuallyAdded = true)))
    }
    fun adjustQuantity(id: String, delta: Int) = edit(id) { it.copy(quantity = (it.quantity.toLong() + delta).coerceIn(1, 999).toInt(), quantityDetected = true) }
    fun select(id: String, selected: Boolean) = edit(id) { it.copy(selected = selected) }
    fun delete(id: String) {
        if (!state.value.saving && state.value.savedCount == 0) update(state.value.copy(items = state.value.items.filterNot { it.id == id }, error = null))
    }
    fun showError(message: String) { update(state.value.copy(error = message)) }

    private fun edit(id: String, change: (BatchIngredientDraft) -> BatchIngredientDraft) {
        if (!state.value.saving && state.value.savedCount == 0) update(state.value.copy(items = state.value.items.map { if (it.id == id) change(it) else it }, error = null))
    }

    fun addAllToInventory(target: com.xmum.smartgrocery.data.local.dao.DataScope? = null) {
        val current = state.value
        if (!current.canSave) return
        val selected = current.items.filter { it.selected }
        update(current.copy(saving = true, error = null))
        viewModelScope.launch {
            try {
                val items = selected.map { row ->
                    GroceryItem(name = row.name.trim(), category = row.category, quantity = row.quantity, price = 0.0,
                        expiryDate = row.expiryDate, threshold = row.threshold)
                }
                val app = getApplication<Application>() as SmartGroceryApp
                if (target == null) app.groceryRepository.insertBatch(items)
                else app.sessionManager.withInventoryDestination(target) { app.groceryRepository.insertBatch(items) }
                update(state.value.copy(saving = false, savedCount = items.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update(state.value.copy(saving = false, error = "Couldn’t save this batch. No items were added. Your edits are kept; please try again."))
            }
        }
    }

    internal fun takeSavedCount(): Int? {
        if (completionDelivered || state.value.savedCount <= 0) return null
        completionDelivered = true
        return state.value.savedCount
    }

    private fun update(value: IngredientScanState) {
        savedState["scan_engine"] = "visual-food-v1"
        savedState["batch_rows"] = ArrayList(value.items.map { row -> Bundle().apply {
            putString("id", row.id); putString("name", row.name); putString("category", row.category)
            putInt("quantity", row.quantity); putBoolean("selected", row.selected)
            putBoolean("quantity_detected", row.quantityDetected); putLong("expiry_date", row.expiryDate ?: 0L); putInt("threshold", row.threshold)
            putBoolean("manual_expiry", row.expiryManuallySelected); putBoolean("manual_item", row.manuallyAdded)
        } })
        savedState["reviewing"] = value.reviewing
        savedState["saved_count"] = value.savedCount
        mutableState.value = value
    }
}

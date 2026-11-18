package com.xmum.smartgrocery.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.InventoryLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class HistorySource(val logs: List<InventoryLog> = emptyList(), val household: Boolean = false,
    val contextName: String = "Personal (private)", val loading: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseHistoryViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as SmartGroceryApp
    private val search = saved.getStateFlow("purchase_search", "")
    private val month = saved.getStateFlow("purchase_this_month", false)
    private val message = MutableStateFlow<String?>(null)
    private val source = app.sessionManager.sessions.onEach { message.value = null }.flatMapLatest { session ->
        val user = session?.userId
        val household = session?.householdId
        if (user == null) flowOf(HistorySource(error = "Sign in to view your purchase history."))
        else combine(app.groceryRepository.purchaseHistory, app.sessionManager.context) { logs, context ->
            // Clear old scope emissions during account/context changes, before Room finishes its new query.
            val scoped = logs.filter { if (household == null) it.householdId == null && it.userId == user else it.householdId == household }
            HistorySource(scoped, household != null, context?.name ?: if (household == null) "Personal (private)" else "Household")
        }.onStart { emit(HistorySource(household = household != null,
            contextName = if (household == null) "Personal (private)" else "Household", loading = true)) }
            .catch { emit(HistorySource(household = household != null, error = "Couldn’t load purchase history. Reopen this screen to retry.")) }
    }
    val state = combine(source, search, month, message) { data, term, monthly, error ->
        PurchaseHistoryState(PurchaseHistoryMapper.records(data.logs, term, monthly, System.currentTimeMillis()),
            data.household, data.contextName, term, monthly, data.loading, error ?: data.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PurchaseHistoryState())

    fun search(value: String) { saved["purchase_search"] = value }
    fun thisMonth(value: Boolean) { saved["purchase_this_month"] = value }
    fun selectHousehold(household: Boolean) {
        message.value = null
        viewModelScope.launch {
            try { app.sessionManager.selectScope(household) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message.value = error.message ?: "Unable to change purchase history scope." }
        }
    }
}

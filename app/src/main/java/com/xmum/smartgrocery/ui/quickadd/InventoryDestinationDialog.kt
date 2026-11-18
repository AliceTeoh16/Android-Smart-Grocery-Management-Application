package com.xmum.smartgrocery.ui.quickadd

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity
import com.xmum.smartgrocery.ui.pantrypal.*
import kotlinx.coroutines.launch

/** Choose the inventory space before voice review looks up existing stock. */
class InventoryDestinationDialog : BottomSheetDialogFragment() {
    private var target by mutableStateOf<DataScope?>(null)
    private var homes by mutableStateOf<List<HouseholdEntity>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val manager = (requireActivity().application as SmartGroceryApp).sessionManager
            val actor = manager.currentUserId()
            if (actor == null || (savedInstanceState?.getString("user")?.let { it != actor } == true)) {
                error = "Sign in and reopen voice input."; return@launch
            }
            val active = manager.currentSession()?.activeHouseholdId
            homes = manager.households().sortedBy { if (it.householdId == active) 0 else 1 }
            target = DataScope(actor, savedInstanceState?.getString("home"))
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        target?.let { outState.putString("user", it.userId); outState.putString("home", it.householdId) }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PantryPalTheme {
                Surface(color = PantryColors.Cream) {
                    Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding()) {
                        Text("Voice inventory destination", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        val household = target?.householdId != null
                        InventoryDestinationCard(target?.householdId?.let { id -> homes.firstOrNull { it.householdId == id }?.name }
                            ?: "Personal (private)", household, homes.map { HouseholdContextOption(it.householdId, it.name) }, {
                            if (it && homes.isEmpty()) error = "Create or join a household before sharing inventory."
                            else { target = target?.copy(householdId = if (it) homes.first().householdId else null); error = null }
                        }, { target = target?.copy(householdId = it) }, enabled = target != null)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            target?.let { parentFragmentManager.setFragmentResult(RESULT,
                                bundleOf("user" to it.userId, "home" to it.householdId)); dismiss() }
                        }, enabled = target != null, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Text("Continue with voice input")
                        }
                        TextButton(onClick = { dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    }
                }
            } }
        }
    companion object { const val TAG = "inventory_destination"; const val RESULT = "voice_inventory_destination" }
}

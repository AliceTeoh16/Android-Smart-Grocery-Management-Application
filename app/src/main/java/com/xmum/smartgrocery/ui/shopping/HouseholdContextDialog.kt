package com.xmum.smartgrocery.ui.shopping

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.ui.pantrypal.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One explicit context controls pantry, shopping and budget ownership together. */
internal object HouseholdContextDialog {
    fun show(owner: Fragment) {
        val fragments = owner.childFragmentManager
        if (!fragments.isStateSaved && fragments.findFragmentByTag(HouseholdContextSheet.TAG) == null)
            HouseholdContextSheet().show(fragments, HouseholdContextSheet.TAG)
    }
}

/** Presentation only: account, scope and sharing operations remain with SessionManager. */
class HouseholdContextSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            id = R.id.household_context_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val manager = (requireActivity().application as SmartGroceryApp).sessionManager
                val scope = rememberCoroutineScope()
                var households by remember { mutableStateOf<List<HouseholdContextOption>>(emptyList()) }
                var selected by remember { mutableStateOf<String?>(null) }
                var managed by remember { mutableStateOf<String?>(null) }
                var loading by remember { mutableStateOf(true) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var mode by rememberSaveable { mutableStateOf(HouseholdContextMode.Choose) }
                var value by rememberSaveable { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    if (manager.currentUserId() == null) {
                        Toast.makeText(requireContext(), "Sign in to manage personal and household data.", Toast.LENGTH_LONG).show()
                        dismiss()
                    } else {
                        households = manager.households().map { HouseholdContextOption(it.householdId, it.name) }
                        val session = manager.currentSession()
                        selected = session?.householdId
                        managed = session?.activeHouseholdId ?: session?.householdId
                        loading = false
                    }
                }
                LaunchedEffect(busy) {
                    isCancelable = !busy
                    dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                        BottomSheetBehavior.from(it).isDraggable = !busy
                    }
                }
                fun perform(action: suspend () -> Unit) {
                    if (busy) return
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            action()
                            dismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Unable to update household." }
                        finally { busy = false }
                    }
                }
                PantryPalTheme {
                    HouseholdContextSheetContent(
                        households = households, selectedHouseholdId = selected,
                        managedHouseholdName = households.firstOrNull { it.id == managed }?.name,
                        loading = loading, busy = busy, error = error, mode = mode, value = value,
                        onValueChange = { value = it; error = null },
                        onModeChange = { mode = it; value = ""; error = null },
                        onSelect = { id -> perform { manager.selectHousehold(id) } },
                        onSubmit = {
                            val input = value.trim()
                            error = when {
                                mode == HouseholdContextMode.Create && input.isBlank() -> "Enter a household name."
                                mode == HouseholdContextMode.Join && !input.matches(Regex("[0-9]{6}")) -> "Enter the 6-digit invite code."
                                else -> null
                            }
                            if (error == null) perform {
                                if (mode == HouseholdContextMode.Create) manager.createHousehold(input)
                                else manager.joinHousehold(input)
                            }
                        },
                        onInvite = {
                            HouseholdInvitationDialog.show(requireParentFragment())
                            dismiss()
                        },
                        onLeave = { managed?.let { id -> perform { manager.leaveHousehold(id) } } },
                        onClose = { dismiss() },
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            BottomSheetBehavior.from(it).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    companion object { const val TAG = "HouseholdContext" }
}

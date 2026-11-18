package com.xmum.smartgrocery.ui.shopping

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity
import com.xmum.smartgrocery.data.local.entity.HouseholdMemberStatus
import com.xmum.smartgrocery.data.local.entity.HouseholdRole
import com.xmum.smartgrocery.data.repository.HouseholdRepository
import com.xmum.smartgrocery.ui.pantrypal.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal object HouseholdManagementDialog {
    fun show(owner: Fragment, state: HouseholdUiState) {
        val id = state.householdId ?: return HouseholdContextDialog.show(owner)
        val fragments = owner.childFragmentManager
        if (!fragments.isStateSaved && fragments.findFragmentByTag(HouseholdManagementSheet.TAG) == null)
            HouseholdManagementSheet().apply {
                arguments = Bundle().apply { putString(HouseholdManagementSheet.HOUSEHOLD_ID, id) }
            }.show(fragments, HouseholdManagementSheet.TAG)
    }
}

private data class ManagedHousehold(val household: HouseholdEntity?, val isOwner: Boolean)

/** The sheet renders Room state and delegates all writes to the existing repository. */
class HouseholdManagementSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val id = requireArguments().getString(HOUSEHOLD_ID)!!
        val repository = HouseholdRepository((requireActivity().application as SmartGroceryApp).database)
        return ComposeView(requireContext()).apply {
            this.id = R.id.household_management_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val live = remember(repository) {
                    combine(repository.household, repository.members) { household, members ->
                        ManagedHousehold(household, members.any { it.householdId == id && it.isCurrentUser &&
                            it.role == HouseholdRole.OWNER && it.status == HouseholdMemberStatus.ACTIVE })
                    }
                }
                val data by live.collectAsStateWithLifecycle(initialValue = null)
                val household = data?.household?.takeIf { it.householdId == id }
                val canEdit = household != null && data?.isOwner == true
                val scope = rememberCoroutineScope()
                var mode by rememberSaveable { mutableStateOf(HouseholdManagementMode.Overview) }
                var name by rememberSaveable { mutableStateOf("") }
                var description by rememberSaveable { mutableStateOf("") }
                var email by rememberSaveable { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var success by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(busy) {
                    isCancelable = !busy
                    dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                        BottomSheetBehavior.from(it).isDraggable = !busy
                    }
                }
                PantryPalTheme {
                    HouseholdManagementSheetContent(
                        householdName = household?.name.orEmpty(), householdDescription = household?.description.orEmpty(),
                        isOwner = canEdit, loading = data == null, available = household != null,
                        mode = mode, name = name, description = description, email = email,
                        busy = busy, error = error, success = success,
                        onModeChange = { next ->
                            if (next == HouseholdManagementMode.Details) {
                                name = household?.name.orEmpty()
                                description = household?.description.orEmpty()
                            }
                            if (next == HouseholdManagementMode.Invite) email = ""
                            mode = next; error = null; success = null
                        },
                        onNameChange = { name = it; error = null },
                        onDescriptionChange = { description = it; error = null },
                        onEmailChange = { email = it; error = null },
                        onSubmit = submit@{
                            if (busy || !canEdit || mode == HouseholdManagementMode.Overview) return@submit
                            val editing = mode == HouseholdManagementMode.Details
                            error = when {
                                editing && name.isBlank() -> "Enter a household name."
                                !editing && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Enter a valid account email."
                                else -> null
                            }
                            if (error != null) return@submit
                            busy = true
                            scope.launch {
                                try {
                                    if (editing) repository.updateDetails(id, name, description)
                                    else repository.inviteMember(id, email.trim())
                                    mode = HouseholdManagementMode.Overview
                                    success = if (editing) "Household details saved." else "Account invited. Share your household code so they can join."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: "Unable to update household." }
                                finally { busy = false }
                            }
                        },
                        onInviteCode = { HouseholdInvitationDialog.show(requireParentFragment()); dismiss() },
                        onChooseHousehold = { HouseholdContextDialog.show(requireParentFragment()); dismiss() },
                        onClose = { dismiss() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        styleHouseholdInvitationDialog(dialog as BottomSheetDialog)
    }

    companion object {
        const val TAG = "HouseholdManagement"
        const val HOUSEHOLD_ID = "household_id"
    }
}

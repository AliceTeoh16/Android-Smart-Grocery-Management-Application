package com.xmum.smartgrocery.ui.quickadd

import android.content.ActivityNotFoundException
import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.xmum.smartgrocery.ui.pantrypal.*
import java.io.File
import java.util.Calendar

/** Visual food batch review. Only Add All to Inventory commits the selected rows. */
class IngredientScanBottomSheetDialog : BottomSheetDialogFragment() {
    private val viewModel: IngredientScanViewModel by viewModels()
    private var target by mutableStateOf<DataScope?>(null)
    private var homes by mutableStateOf<List<HouseholdEntity>>(emptyList())
    private var destinationError by mutableStateOf<String?>(null)
    private val ready get() = target?.let { it.householdId == null || homes.any { h -> h.householdId == it.householdId } } == true
    private val destinationName get() = target?.householdId?.let { id -> homes.firstOrNull { it.householdId == id }?.name ?: "Unavailable household" } ?: "Personal (private)"
    private var cameraUri: Uri? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::scan)
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = cameraUri
        cameraUri = null
        if (captured && uri != null) viewModel.scan(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraUri = savedInstanceState?.getString("camera_uri")?.let(Uri::parse)
        lifecycleScope.launch {
            val manager = (requireActivity().application as SmartGroceryApp).sessionManager
            val actor = manager.currentUserId()
            val original = savedInstanceState?.getString("destination_user") ?: arguments?.getString("destination_user") ?: actor
            if (actor == null || actor != original) { destinationError = "Account changed. Reopen the scanner."; return@launch }
            homes = manager.households()
            target = DataScope(actor, if (savedInstanceState?.containsKey("destination_user") == true)
                savedInstanceState.getString("destination_home") else arguments?.getString("destination_home"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("camera_uri", cameraUri?.toString())
        target?.let { outState.putString("destination_user", it.userId); outState.putString("destination_home", it.householdId) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.state.collectAsStateWithLifecycle()
                androidx.compose.runtime.SideEffect {
                    isCancelable = !state.saving
                    (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.isDraggable = !state.saving
                }
                LaunchedEffect(state.savedCount) {
                    if (state.savedCount > 0) viewLifecycleOwner.lifecycle.withResumed {
                        viewModel.takeSavedCount()?.let { count ->
                            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf("added_count" to count))
                            dismiss()
                        }
                    }
                }
                PantryPalTheme {
                    BatchIngredientReviewSheet(state, ::takePhoto, ::choosePhoto, { if (!viewModel.state.value.saving) dismiss() },
                        viewModel::rename, viewModel::changeCategory, viewModel::adjustQuantity,
                        viewModel::select, viewModel::delete, ::chooseExpiry, viewModel::addManualItem, {
                            if (ready && viewModel.state.value.canSave) {
                                isCancelable = false
                                (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.isDraggable = false
                                viewModel.addAllToInventory(target)
                            }
                        }, destinationReady = ready, destinationContent = {
                            InventoryDestinationCard(destinationName, target?.householdId != null,
                                homes.map { HouseholdContextOption(it.householdId, it.name) }, { household ->
                                    if (!state.saving && target != null) {
                                        if (household && homes.isEmpty()) destinationError = "Create or join a household before sharing inventory."
                                        else { target = target!!.withHouseholdId(if (household) homes.first().householdId else null); destinationError = null }
                                    }
                                }, { id -> if (!state.saving && homes.any { it.householdId == id }) target = target?.withHouseholdId(id) },
                                enabled = ready && !state.saving)
                            destinationError?.let { androidx.compose.material3.Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        })
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun choosePhoto() {
        try { photoPicker.launch("image/*") }
        catch (_: ActivityNotFoundException) { viewModel.showError("No photo picker is available. Try taking a photo or enter details manually.") }
    }

    private fun chooseExpiry(id: String) {
        if (viewModel.state.value.saving) return
        val item = viewModel.state.value.items.firstOrNull { it.id == id } ?: return
        val date = Calendar.getInstance().apply { timeInMillis = item.expiryDate ?: System.currentTimeMillis() }
        DatePickerDialog(requireContext(), { _, year, month, day ->
            date.set(year, month, day)
            viewModel.changeExpiry(id, date.timeInMillis)
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).apply {
            setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Not Set") { _, _ -> viewModel.changeExpiry(id, null) }
        }.show()
    }

    private fun takePhoto() {
        try {
            val directory = File(requireContext().cacheDir, "ingredient-scans").apply { mkdirs() }
            // Only old files created by this scanner are removed; gallery photos are never changed.
            directory.listFiles()?.filter { it.name.startsWith("scan-") && it.lastModified() < System.currentTimeMillis() - 86400000 }
                ?.forEach { it.delete() }
            cameraUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.ingredientphotos",
                File.createTempFile("scan-", ".jpg", directory))
            camera.launch(cameraUri!!)
        } catch (_: ActivityNotFoundException) {
            cameraUri = null
            viewModel.showError("No camera app is available. Choose a photo instead.")
        } catch (_: Exception) {
            cameraUri = null
            viewModel.showError("Couldn’t open the camera. Choose a photo or check your device’s camera access.")
        }
    }

    companion object {
        fun newInstance(target: DataScope, name: String) = IngredientScanBottomSheetDialog().apply {
            arguments = bundleOf("destination_user" to target.userId, "destination_home" to target.householdId, "destination_name" to name)
        }
        const val TAG = "ingredient_scan"
        const val REQUEST_KEY = "ingredient_scan_selection"
    }
}


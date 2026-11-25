package com.xmum.smartgrocery.ui.shopping

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.entity.HouseholdEntity
import com.xmum.smartgrocery.data.repository.HouseholdRepository
import com.xmum.smartgrocery.data.sharing.SharingRuntime
import com.xmum.smartgrocery.ui.pantrypal.HouseholdInvitationSheetContent
import com.xmum.smartgrocery.ui.pantrypal.PantryPalTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Codes and links use the current household's existing local/server sharing context. */
internal object HouseholdInvitationDialog {
    fun show(owner: Fragment): Unit = with(owner) {
        viewLifecycleOwner.lifecycleScope.launch {
            val manager = (requireActivity().application as SmartGroceryApp).sessionManager
            val session = manager.currentSession()
            val selected = session?.activeHouseholdId ?: session?.householdId
            val household = manager.households().firstOrNull { it.householdId == selected }
            if (household == null) { HouseholdContextDialog.show(owner); return@launch }
            val fragments = childFragmentManager
            if (!fragments.isStateSaved && fragments.findFragmentByTag(HouseholdInvitationSheet.TAG) == null)
                HouseholdInvitationSheet().apply {
                    arguments = Bundle().apply { putString(HouseholdInvitationSheet.HOUSEHOLD_ID, household.householdId) }
                }.show(fragments, HouseholdInvitationSheet.TAG)
        }
    }

    fun share(owner: Fragment, householdId: String? = null): Unit = with(owner) {
        viewLifecycleOwner.lifecycleScope.launch {
            val manager = (requireActivity().application as SmartGroceryApp).sessionManager
            val selected = householdId ?: manager.currentSession()?.householdId
            val household = manager.households().firstOrNull { it.householdId == selected }
            if (household == null) { HouseholdContextDialog.show(owner); return@launch }
            val app = requireActivity().application as SmartGroceryApp
            val link = app.database.sharingDao().household(household.householdId)
            if (com.xmum.smartgrocery.data.sharing.SharingRuntime.forDatabase(app.database) != null && link == null) {
                Toast.makeText(requireContext(), "Enable cross-device sharing for this household first.", Toast.LENGTH_LONG).show()
                return@launch
            }
            startActivity(Intent.createChooser(HouseholdInvite.shareIntent(household.name, household.joinCode, link?.serverUrl), "Share invitation link"))
        }
    }

    fun createQrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2
        )
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { index ->
            if (bitMatrix[index % size, index / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

}

private data class HouseholdInvitationData(val household: HouseholdEntity?, val canShare: Boolean)

class HouseholdInvitationSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val expectedId = requireArguments().getString(HOUSEHOLD_ID)!!
        val app = requireActivity().application as SmartGroceryApp
        val repository = HouseholdRepository(app.database)
        return ComposeView(requireContext()).apply {
            id = R.id.household_invitation_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val live = remember(repository) {
                    combine(repository.household, app.database.sharingDao().activeHousehold()) { home, link ->
                        HouseholdInvitationData(home, SharingRuntime.forDatabase(app.database) == null ||
                            link?.localHouseholdId == home?.householdId && link != null)
                    }
                }
                val data by live.collectAsStateWithLifecycle(initialValue = null)
                val home = data?.household?.takeIf { it.householdId == expectedId }
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied, home?.joinCode) {
                    if (copied) { delay(2500); copied = false }
                }
                PantryPalTheme {
                    HouseholdInvitationSheetContent(
                        householdName = home?.name.orEmpty(), inviteCode = home?.joinCode.orEmpty(),
                        loading = data == null, available = home != null,
                        canShare = data?.canShare == true, copied = copied,
                        onCopy = {
                            home?.let {
                                (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Household invite code", it.joinCode))
                                copied = true
                            }
                        },
                        onShare = { HouseholdInvitationDialog.share(this@HouseholdInvitationSheet, expectedId) },
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
        const val TAG = "HouseholdInvitation"
        const val HOUSEHOLD_ID = "household_id"
    }
}

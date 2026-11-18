package com.xmum.smartgrocery.ui.shopping

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Appearance only; the original invitation form owns all input and action handling. */
internal fun styleHouseholdInvitationDialog(dialog: BottomSheetDialog) {
    dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
        ColorDrawable(Color.TRANSPARENT)
    dialog.behavior.apply {
        skipCollapsed = true
        state = BottomSheetBehavior.STATE_EXPANDED
    }
}

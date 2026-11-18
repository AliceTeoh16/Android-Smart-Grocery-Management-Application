package com.xmum.smartgrocery.ui.shopping

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.databinding.DialogShoppingLinkQrBinding

/** Presentation only: the original platform buttons retain copy and dismissal ownership. */
internal fun styleSharingLinkQrDialog(dialog: AlertDialog, content: DialogShoppingLinkQrBinding) {
    content.buttonCopyShoppingLink.setOnClickListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    content.buttonCloseShoppingLink.setOnClickListener {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }
    (dialog.getButton(AlertDialog.BUTTON_POSITIVE).parent as? View)?.visibility = View.GONE
    val density = dialog.context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    dialog.window?.apply {
        setBackgroundDrawable(ContextCompat.getDrawable(dialog.context, R.drawable.pantry_share_dialog_bg))
        setLayout((dialog.context.resources.displayMetrics.widthPixels - dp(48)).coerceAtMost(dp(560)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

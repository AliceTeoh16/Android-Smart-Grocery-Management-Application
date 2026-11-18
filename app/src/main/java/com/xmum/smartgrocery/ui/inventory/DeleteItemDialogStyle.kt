package com.xmum.smartgrocery.ui.inventory

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.databinding.DialogDeleteItemConfirmationBinding

/** Only replaces presentation; the original dialog buttons own deletion and cancellation. */
internal fun styleDeleteItemDialog(dialog: AlertDialog, content: DialogDeleteItemConfirmationBinding) {
    content.buttonConfirmDeleteItem.setOnClickListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }
    content.buttonCancelDeleteItem.setOnClickListener {
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

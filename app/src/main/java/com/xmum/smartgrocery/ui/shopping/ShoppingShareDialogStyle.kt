package com.xmum.smartgrocery.ui.shopping

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.xmum.smartgrocery.R

/** Renders the original option strings; AlertDialog continues to own every option click. */
internal class ShoppingShareOptionsAdapter(context: Context, options: Array<String>) :
    ArrayAdapter<String>(context, R.layout.item_shopping_share_option, R.id.text_share_option, options) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getView(position, convertView, parent)
        val primary = position == 2
        val foreground = if (primary) Color.WHITE else Color.rgb(49, 91, 64)
        row.findViewById<View>(R.id.card_share_option).setBackgroundResource(
            if (primary) R.drawable.pantry_share_primary_bg else R.drawable.pantry_share_option_bg
        )
        row.findViewById<View>(R.id.container_share_icon).backgroundTintList = ColorStateList.valueOf(
            if (primary) Color.argb(30, 255, 255, 255) else Color.rgb(230, 239, 222)
        )
        row.findViewById<TextView>(R.id.text_share_option).setTextColor(foreground)
        row.findViewById<ImageView>(R.id.image_share_option).apply {
            setImageResource(when (position) {
                0 -> R.drawable.pantry_share_copy
                1 -> R.drawable.pantry_share_qr
                2 -> R.drawable.ic_share_24
                else -> R.drawable.ic_group_24
            })
            imageTintList = ColorStateList.valueOf(foreground)
        }
        row.findViewById<ImageView>(R.id.image_share_chevron).imageTintList = ColorStateList.valueOf(foreground)
        return row
    }
}

/** Scoped appearance only, leaving the platform's selection and dismissal behavior intact. */
internal fun styleShoppingShareDialog(dialog: AlertDialog) {
    val density = dialog.context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    dialog.listView.apply {
        setPadding(dp(24), dp(4), dp(24), dp(100))
        clipToPadding = false
        divider = null
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
    }
    dialog.window?.apply {
        setBackgroundDrawable(ContextCompat.getDrawable(dialog.context, R.drawable.pantry_share_dialog_bg))
        setLayout((dialog.context.resources.displayMetrics.widthPixels - dp(48)).coerceAtMost(dp(560)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

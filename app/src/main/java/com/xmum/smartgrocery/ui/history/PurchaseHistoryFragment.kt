package com.xmum.smartgrocery.ui.history

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.ui.pantrypal.PantryPalTheme
import com.xmum.smartgrocery.ui.pantrypal.PurchaseHistoryScreen
import com.xmum.smartgrocery.ui.shopping.HouseholdContextDialog

class PurchaseHistoryFragment : DialogFragment() {
    private val model: PurchaseHistoryViewModel by viewModels()

    // Embedded in the main fragment stack, or displayed above the retained profile dialog.
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = Dialog(requireContext(), R.style.Theme_SmartGrocery)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            id = R.id.purchase_history_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by model.state.collectAsStateWithLifecycle()
                PantryPalTheme {
                    PurchaseHistoryScreen(state, model::search, model::thisMonth, model::selectHousehold,
                        onContext = { HouseholdContextDialog.show(this@PurchaseHistoryFragment) },
                        onBack = { parentFragmentManager.popBackStack() })
                }
            }
        }

    override fun onStart() {
        super.onStart()
        if (showsDialog) dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = 0xFFF9F8F3.toInt()
            navigationBarColor = statusBarColor
        }
    }

    companion object {
        const val TAG = "PurchaseHistory"
        const val BACK_STACK = "purchase_history"
    }
}

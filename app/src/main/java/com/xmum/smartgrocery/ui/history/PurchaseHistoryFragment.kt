package com.xmum.smartgrocery.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.pantrypal.PantryEntry
import com.xmum.smartgrocery.ui.pantrypal.PantryPalTheme
import com.xmum.smartgrocery.ui.pantrypal.PurchaseHistoryScreen
import com.xmum.smartgrocery.ui.shopping.HouseholdContextDialog

class PurchaseHistoryFragment : Fragment() {
    private val model: PurchaseHistoryViewModel by viewModels()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            id = R.id.purchase_history_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by model.state.collectAsStateWithLifecycle()
                PantryPalTheme {
                    PurchaseHistoryScreen(state, model::search, model::thisMonth, model::selectHousehold,
                        onContext = { HouseholdContextDialog.show(this@PurchaseHistoryFragment) },
                        onBack = { (activity as? MainActivity)?.showPantryFlow(PantryEntry.Home) })
                }
            }
        }
}

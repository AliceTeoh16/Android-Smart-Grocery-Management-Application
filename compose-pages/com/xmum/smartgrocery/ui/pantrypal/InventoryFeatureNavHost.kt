package com.xmum.smartgrocery.ui.pantrypal

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.xmum.smartgrocery.ui.quickadd.QuickAddBottomSheetDialog

internal const val INVENTORY_ROUTE = "inventory"
internal const val ADD_GROCERY_ROUTE = "inventory/add-grocery-item"

/** Keep the inventory fragment and its state owner alive while the full-screen form is open. */
@Composable
internal fun InventoryFeatureNavHost(
    navController: NavHostController, inventoryView: View, fragmentManager: FragmentManager,
    onFullScreen: (Boolean) -> Unit,
    inventoryContent: (@Composable () -> Unit)? = null,
) {
    NavHost(navController, startDestination = INVENTORY_ROUTE) {
        composable(INVENTORY_ROUTE) {
            if (inventoryContent != null) inventoryContent() else {
                AndroidView(factory = {
                    (inventoryView.parent as? ViewGroup)?.removeView(inventoryView)
                    inventoryView
                }, modifier = Modifier.fillMaxSize())
            }
        }
        composable(ADD_GROCERY_ROUTE) {
            DisposableEffect(Unit) {
                onFullScreen(true)
                onDispose { onFullScreen(false) }
            }
            EmbeddedGroceryForm(fragmentManager, onClose = { navController.popBackStack() })
        }
    }
}

/** Mount the original form owner as a child fragment, without creating or showing a dialog. */
@Composable
private fun EmbeddedGroceryForm(fragmentManager: FragmentManager, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = remember { FrameLayout(context) }
    val currentOnClose by rememberUpdatedState(onClose)
    AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())
    DisposableEffect(fragmentManager, container) {
        val tag = "FullScreenAddGroceryItem"
        val form = fragmentManager.findFragmentByTag(tag) ?: QuickAddBottomSheetDialog.newInstance().apply {
            requireArguments().putBoolean(QuickAddBottomSheetDialog.ARG_FULL_SCREEN, true)
        }
        fun attach(view: View) {
            if (view.parent !== container) {
                (view.parent as? ViewGroup)?.removeView(view)
                container.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        }
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f === form) attach(v)
            }
            override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
                if (f === form) currentOnClose()
            }
        }
        fragmentManager.registerFragmentLifecycleCallbacks(callbacks, false)
        form.view?.let(::attach)
        if (!form.isAdded && !fragmentManager.isStateSaved) {
            fragmentManager.beginTransaction().add(form, tag).commit()
        }
        onDispose {
            fragmentManager.unregisterFragmentLifecycleCallbacks(callbacks)
            container.removeAllViews()
            if (form.isAdded && !fragmentManager.isStateSaved) {
                fragmentManager.beginTransaction().remove(form).commit()
            }
        }
    }
}

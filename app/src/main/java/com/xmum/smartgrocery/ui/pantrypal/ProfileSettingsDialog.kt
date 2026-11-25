package com.xmum.smartgrocery.ui.pantrypal

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.xmum.smartgrocery.ui.shopping.HouseholdContextDialog
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.DialogFragment
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import com.xmum.smartgrocery.ui.main.MainActivity
import com.xmum.smartgrocery.ui.history.PurchaseHistoryFragment
import java.util.Locale

/** Full-screen presentation of the existing account details and sign-out action. */
class ProfileSettingsDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?) = Dialog(requireContext(), R.style.Theme_SmartGrocery)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val session = LocalAuthSession(requireContext())
        val profile = session.currentProfile()
        val warningDays = (requireActivity().application as SmartGroceryApp).preferencesRepository.expiryWarningDays.value
        return ComposeView(requireContext()).apply {
            id = R.id.profile_settings_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dataContext by session.manager.context.collectAsState(initial = null)
                var privacyOpen by remember { mutableStateOf(false) }
                PantryPalTheme {
                    ProfileSettingsScreen(
                        displayName = profile.displayName, email = profile.email,
                        initials = profile.displayName.split(' ').filter { it.isNotBlank() }.take(2)
                            .joinToString("") { it.first().uppercaseChar().toString() },
                        householdName = dataContext?.name ?: "Personal (private)",
                        householdSubtitle = "Choose personal or household data",
                        notificationsEnabled = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled(),
                        expiryWarningDays = warningDays, language = Locale.getDefault().displayLanguage,
                        onBack = { dismiss() },
                        onHousehold = { HouseholdContextDialog.show(this@ProfileSettingsDialog) },
                        onPurchaseHistory = {
                            if (childFragmentManager.findFragmentByTag(PurchaseHistoryFragment.TAG) == null) {
                                PurchaseHistoryFragment().show(
                                    childFragmentManager.beginTransaction()
                                        .addToBackStack(PurchaseHistoryFragment.BACK_STACK),
                                    PurchaseHistoryFragment.TAG,
                                )
                            }
                        },
                        onPrivacyPolicy = { privacyOpen = true },
                        onSignOut = {
                            viewLifecycleOwner.lifecycleScope.launch {
                                session.signOut()
                                (activity as? MainActivity)?.showPantryFlow(PantryEntry.Welcome)
                                dismiss()
                            }
                        },
                    )
                    if (privacyOpen) AlertDialog(
                        onDismissRequest = { privacyOpen = false }, title = { Text("Privacy Policy") },
                        text = { Text("No Privacy Policy document has been configured for this app. This app currently uses local sign-in and device storage.") },
                        confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("Close") } },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = 0xFFF9F8F3.toInt()
            navigationBarColor = statusBarColor
        }
    }

    companion object { const val TAG = "ProfileSettings" }
}

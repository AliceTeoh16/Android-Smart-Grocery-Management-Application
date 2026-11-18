package com.xmum.smartgrocery.ui.pantrypal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import com.xmum.smartgrocery.ui.main.MainActivity
import kotlinx.coroutines.flow.catch

/** Compose island in the existing fragment app; existing feature fragments keep their owners. */
class PantryPalFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val session = LocalAuthSession(requireContext())
        val app = requireActivity().application as SmartGroceryApp
        val entry = arguments?.getString(ARG_ENTRY)?.let { runCatching { PantryEntry.valueOf(it) }.getOrNull() }
            ?: if (session.currentProfile().signedIn) PantryEntry.Home else PantryEntry.Welcome
        return ComposeView(requireContext()).apply {
            // A stable ID lets Fragment's saved-state registry restore the NavHost's form stack.
            id = R.id.pantry_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val initialState = remember {
                    HomeOverviewMapper.map(
                        DashboardSnapshot(emptyList(), emptyList(), emptyList(), null, 0.0),
                        session.currentProfile(), app.preferencesRepository.expiryWarningDays.value, System.currentTimeMillis(),
                    ).copy(isLoading = true)
                }
                val liveState = remember {
                    HomeOverviewDataSource(app, session).state.catch {
                        emit(initialState.copy(isLoading = false, dataError = "Couldn’t load pantry data. Return to Home to retry."))
                    }
                }
                val state by liveState.collectAsStateWithLifecycle(initialValue = initialState, lifecycleOwner = viewLifecycleOwner)
                var dialog by rememberSaveable { mutableStateOf<String?>(null) }
                PantryPalTheme {
                    AuthNavGraph(
                        entry = entry, homeState = state,
                        onLogin = { email, password -> session.signIn(email, password).toAuthResult() },
                        onSignUp = { name, email, password -> session.createAccount(name, email, password).toAuthResult() },
                        onSendResetLink = { AuthResult.Failure(LocalAuthSession.RESET_UNAVAILABLE) },
                        resetEmailAvailable = false,
                        onConfigureSharing = { com.xmum.smartgrocery.ui.shopping.SharingSetupDialog.show(this@PantryPalFragment) },
                        onTermsOfService = { dialog = "terms" },
                        onPrivacyPolicy = { dialog = "privacy" },
                        onHomeAction = { action ->
                            when (action) {
                                PantryAction.Profile -> ProfileSettingsDialog().show(childFragmentManager, ProfileSettingsDialog.TAG)
                                PantryAction.AllActivity -> dialog = "activity"
                                else -> (activity as? MainActivity)?.openPantryAction(action)
                            }
                        },
                    )
                    when (dialog) {
                        "activity" -> AlertDialog(
                            onDismissRequest = { dialog = null }, title = { Text("Around your pantry") },
                            text = {
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (state.activity.isEmpty()) item { Text("No recent activity yet.") }
                                    items(state.activity, key = { it.id }) { ActivityRow(it) }
                                }
                            },
                            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                        )
                        "terms", "privacy" -> AlertDialog(
                            onDismissRequest = { dialog = null },
                            title = { Text(if (dialog == "terms") "Terms of Service" else "Privacy Policy") },
                            text = { Text("No ${if (dialog == "terms") "Terms of Service" else "Privacy Policy"} document has been configured for this app. This app currently uses local sign-in and device storage.") },
                            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                        )
                    }
                }
            }
        }
    }

    private fun String?.toAuthResult(): AuthResult = if (this == null) AuthResult.Success else AuthResult.Failure(this)

    companion object {
        private const val ARG_ENTRY = "pantry_entry"
        fun newInstance(entry: PantryEntry) = PantryPalFragment().apply {
            arguments = Bundle().apply { putString(ARG_ENTRY, entry.name) }
        }
    }
}

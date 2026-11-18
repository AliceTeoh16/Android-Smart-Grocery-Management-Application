package com.xmum.smartgrocery.ui.shopping

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.sharing.SharingRuntime
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import kotlinx.coroutines.launch

internal object SharingSetupDialog {
    fun show(owner: Fragment) = with(owner) {
        val runtime = SharingRuntime.forDatabase((requireActivity().application as SmartGroceryApp).database) ?: return
        val auth = LocalAuthSession(requireContext())
        val profile = auth.currentProfile()
        val server = EditText(requireContext()).apply { hint = "Sharing server address"; setText(runtime.serverUrl.value); setSingleLine() }
        val password = EditText(requireContext()).apply {
            hint = "Account password to connect"; setSingleLine(); isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fields = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 0)
            addView(server); if (profile.signedIn) addView(password)
        }
        val dialog = AlertDialog.Builder(requireContext()).setTitle("Household sharing")
            .setMessage(if (profile.signedIn) "Connect ${profile.email}. Use the same sharing server on every device. Personal items stay on this device."
                else "Use the same sharing server address on every device, then sign in or create an account.")
            .setView(fields).setPositiveButton(if (profile.signedIn) "Connect" else "Save server", null)
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val validation = runCatching { runtime.configureServer(server.text.toString()) }
                if (validation.isFailure) { server.error = validation.exceptionOrNull()?.message; return@setOnClickListener }
                if (!profile.signedIn) { dialog.dismiss(); return@setOnClickListener }
                if (password.text.isNullOrEmpty()) { password.error = "Enter your account password."; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val error = auth.signIn(profile.email, password.text.toString())
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    if (error != null) password.error = error
                    else { Toast.makeText(requireContext(), "Account connected for household sharing.", Toast.LENGTH_SHORT).show(); dialog.dismiss() }
                }
            }
        }
        dialog.show()
    }
}

package com.xmum.smartgrocery.ui.auth

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.databinding.BottomSheetAuthBinding

class AuthBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAuthBinding? = null
    private val binding get() = _binding!!

    private var isCreateAccountMode = false

    companion object {
        const val REQUEST_KEY = "auth_result"
        const val RESULT_SIGNED_IN = "signed_in"
        const val RESULT_EMAIL = "email"

        fun newInstance(): AuthBottomSheetDialog = AuthBottomSheetDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateMode()
        setupListeners()
    }

    private fun setupListeners() {
        binding.buttonGoogle.setOnClickListener {
            Toast.makeText(requireContext(), "Google sign-in is not configured. Use your local account.", Toast.LENGTH_LONG).show()
        }

        binding.textForgotPassword.setOnClickListener {
            val email = binding.editEmail.text?.toString()?.trim().orEmpty()
            if (!isEmailValid(email)) {
                binding.inputEmail.error = "Enter your email first"
                return@setOnClickListener
            }
            binding.inputEmail.error = null
            Toast.makeText(requireContext(), LocalAuthSession.RESET_UNAVAILABLE, Toast.LENGTH_LONG).show()
        }

        binding.buttonPrimaryAuth.setOnClickListener {
            submitEmailAuth()
        }

        binding.buttonToggleMode.setOnClickListener {
            isCreateAccountMode = !isCreateAccountMode
            clearErrors()
            updateMode()
        }

        binding.buttonJoinHousehold.setOnClickListener {
            showJoinHouseholdDialog()
        }
    }

    private fun submitEmailAuth() {
        val email = binding.editEmail.text?.toString()?.trim().orEmpty()
        val password = binding.editPassword.text?.toString().orEmpty()

        var isValid = true
        if (!isEmailValid(email)) {
            binding.inputEmail.error = "Enter a valid email address"
            isValid = false
        } else {
            binding.inputEmail.error = null
        }

        if (password.length < 6) {
            binding.inputPassword.error = "Use at least 6 characters"
            isValid = false
        } else {
            binding.inputPassword.error = null
        }

        if (!isValid) return

        val displayName = email.substringBefore("@")
            .replaceFirstChar { char -> char.uppercaseChar() }

        viewLifecycleOwner.lifecycleScope.launch {
            val session = LocalAuthSession(requireContext())
            val error = if (isCreateAccountMode) session.createAccount(displayName, email, password) else session.signIn(email, password)
            if (error != null) { binding.inputPassword.error = error; return@launch }
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_SIGNED_IN to true, RESULT_EMAIL to email))
            dismiss()
        }
    }

    private fun showJoinHouseholdDialog() {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(requireContext()).apply {
            hint = "6-digit household code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
        val scanButton = MaterialButton(requireContext()).apply {
            text = "Scan QR Code"
            setOnClickListener {
                Toast.makeText(requireContext(), "QR scanner placeholder for prototype", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(input)
        content.addView(scanButton)

        AlertDialog.Builder(requireContext())
            .setTitle("Join Existing Household")
            .setMessage("Enter a household join code or scan a QR code from a member or family.")
            .setView(content)
            .setPositiveButton("Join") { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = runCatching { LocalAuthSession(requireContext()).manager.joinHousehold(code) }
                    Toast.makeText(requireContext(), result.fold({ "Joined " + it.name }, { it.message ?: "Unable to join household" }), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateMode() {
        binding.textModeLabel.text = if (isCreateAccountMode) {
            getString(R.string.auth_create_mode_label)
        } else {
            getString(R.string.auth_sign_in_mode_label)
        }
        binding.buttonPrimaryAuth.text = if (isCreateAccountMode) {
            getString(R.string.auth_create_account)
        } else {
            getString(R.string.auth_sign_in)
        }
        binding.buttonToggleMode.text = if (isCreateAccountMode) {
            getString(R.string.auth_toggle_sign_in)
        } else {
            getString(R.string.auth_toggle_sign_up)
        }
        binding.textForgotPassword.visibility = if (isCreateAccountMode) View.INVISIBLE else View.VISIBLE
    }

    private fun clearErrors() {
        binding.inputEmail.error = null
        binding.inputPassword.error = null
    }

    private fun isEmailValid(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

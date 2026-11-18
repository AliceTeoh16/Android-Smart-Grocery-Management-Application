package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onSendResetLink: suspend (email: String) -> AuthResult,
    modifier: Modifier = Modifier,
    initialEmail: String = "",
    // Supply the actual expiration configured in your authentication backend.
    resetLinkExpiryMinutes: Int = 30,
    resetEmailAvailable: Boolean = true,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val submission = remember { FormSubmission() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val submit: () -> Unit = {
        attempted = true
        if (validEmail(email)) {
            focus.clearFocus()
            submission.submit(scope, { onSendResetLink(email.trim()) })
        }
    }

    PantryPage(modifier) {
        AuthHeader("Reset password", onBack)
        Spacer(Modifier.height(64.dp))
        IconTile(PantryIcons.Mail, large = true)
        Spacer(Modifier.height(24.dp))
        Text("Let’s get you back to your pantry.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        Text("Forgot your password? No worries. Enter the\nemail linked to your account and we’ll send a\nreset link.",
            color = PantryColors.Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        PantryField("Email address", email, { email = it }, "aisha.lim@email.com", PantryIcons.Mail,
            enabled = !submission.loading, keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, onDone = submit)
        Spacer(Modifier.height(12.dp))
        FormMessage(if (attempted && !validEmail(email)) "Enter a valid email address." else submission.error)
        PrimaryCta(if (submission.completed) "Send another link" else "Send reset link", submit, loading = submission.loading)
        FormMessage(if (submission.completed) "If an account exists for this email, you’ll receive a reset link shortly." else null, isError = false)
        Spacer(Modifier.height(12.dp))
        Text(if (resetEmailAvailable) "For your security, the link expires after $resetLinkExpiryMinutes minutes."
            else "Email recovery isn’t configured for this local sign-in prototype.",
            color = PantryColors.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onNavigateToLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Pastel, contentColor = PantryColors.Green),
        ) { Text("Back to log in", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(40.dp))
        Spacer(Modifier.weight(1f))
        InfoCard(PantryIcons.Help, "Check your spam folder and make sure your email address is spelled correctly.", "Can’t find the email?")
    }
}

@Preview(name = "Reset password · 412 × 892", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
private fun ForgotPasswordScreenPreview() = PantryPalTheme {
    ForgotPasswordScreen({}, {}, { AuthResult.Success }, initialEmail = "aisha.lim@email.com")
}

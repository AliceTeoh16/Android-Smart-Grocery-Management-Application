package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xmum.smartgrocery.R
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onNavigateToSignUp: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    onNavigateToHome: () -> Unit,
    onLogin: suspend (email: String, password: String) -> AuthResult,
    modifier: Modifier = Modifier,
    initialEmail: String = "",
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    // Passwords are deliberately not persisted into saved instance state.
    var password by remember { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val submission = remember { FormSubmission() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val validation = when {
        !validEmail(email) -> "Enter a valid email address."
        password.isEmpty() -> "Enter your password."
        else -> null
    }
    val submit: () -> Unit = {
        attempted = true
        if (validation == null) {
            focus.clearFocus()
            submission.submit(scope, { onLogin(email.trim(), password) }, onNavigateToHome)
        }
    }

    PantryPage(modifier) {
        AuthHeader("Log in", onBack)
        Spacer(Modifier.height(52.dp))
        IconTile(PantryIcons.Sprout)
        Spacer(Modifier.height(20.dp))
        Text("Welcome back, pantry pal.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(18.dp))
        Text("Your pantry, people, and next great meal are\nright where you left them.",
            color = PantryColors.Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        PantryField("Email address", email, { email = it }, "aisha.lim@email.com", PantryIcons.Mail,
            enabled = !submission.loading, keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        PantryField("Password", password, { password = it }, "Enter your password", PantryIcons.EyeOff,
            password = true, enabled = !submission.loading, imeAction = ImeAction.Done, onDone = submit)
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNavigateToForgotPassword, contentPadding = PaddingValues(0.dp)) {
                Text("Forgot password?", style = MaterialTheme.typography.labelMedium)
            }
        }
        FormMessage(if (attempted) validation ?: submission.error else null)
        PrimaryCta("Log in", submit, loading = submission.loading)
        AccountPrompt(stringResource(R.string.new_to_app, stringResource(R.string.app_name)), "Sign up", onNavigateToSignUp)
        Spacer(Modifier.height(32.dp))
        Spacer(Modifier.weight(1f))
        InfoCard(PantryIcons.People, "One shared pantry. Fewer “did we buy\nmilk?” moments.")
    }
}

@Preview(name = "Log in · 412 × 892", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
private fun LoginScreenPreview() = PantryPalTheme {
    LoginScreen({}, {}, {}, {}, { _, _ -> AuthResult.Success }, initialEmail = "aisha.lim@email.com")
}

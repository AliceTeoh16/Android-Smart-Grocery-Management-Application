package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun SignUpScreen(
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToHome: () -> Unit,
    onTermsOfService: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onSignUp: suspend (name: String, email: String, password: String) -> AuthResult,
    modifier: Modifier = Modifier,
    initialName: String = "",
    initialEmail: String = "",
    onConfigureSharing: () -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val submission = remember { FormSubmission() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val validation = when {
        name.isBlank() -> "Enter your full name."
        !validEmail(email) -> "Enter a valid email address."
        password.length < 8 || password.none { it.isDigit() } -> "Use at least 8 characters, with a number."
        !agreed -> "Agree to the Terms of Service and Privacy Policy to continue."
        else -> null
    }
    val submit: () -> Unit = {
        attempted = true
        if (validation == null) {
            focus.clearFocus()
            submission.submit(scope, { onSignUp(name.trim(), email.trim(), password) }, onNavigateToHome)
        }
    }

    PantryPage(modifier) {
        AuthHeader("Create account", onBack)
        Spacer(Modifier.height(44.dp))
        Eyebrow("A FRESH START")
        Spacer(Modifier.height(14.dp))
        Text("Make room for better grocery days.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(14.dp))
        Text("Create your account. Then start a pantry or join\nyour household.",
            color = PantryColors.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        PantryField("Full name", name, { name = it }, "Aisha Lim", PantryIcons.Person, enabled = !submission.loading)
        Spacer(Modifier.height(12.dp))
        PantryField("Email address", email, { email = it }, "aisha.lim@email.com", PantryIcons.Mail,
            enabled = !submission.loading, keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        PantryField("Password", password, { password = it }, "Create a password", PantryIcons.EyeOff,
            password = true, enabled = !submission.loading, imeAction = ImeAction.Done, onDone = submit)
        Spacer(Modifier.height(8.dp))
        Text("Use at least 8 characters, with a number.", color = PantryColors.Muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = agreed, onCheckedChange = { agreed = it }, enabled = !submission.loading,
                modifier = Modifier.size(48.dp).offset(x = (-13).dp)
                    .semantics { contentDescription = "Agree to Terms of Service and Privacy Policy" },
                colors = CheckboxDefaults.colors(checkedColor = PantryColors.Green),
            )
            val linkStyle = TextLinkStyles(style = SpanStyle(color = PantryColors.Green, fontWeight = FontWeight.SemiBold))
            Text(
                buildAnnotatedString {
                    append("I agree to the ")
                    withLink(LinkAnnotation.Clickable("terms", linkStyle) { onTermsOfService() }) { append("Terms of Service") }
                    append(" and ")
                    withLink(LinkAnnotation.Clickable("privacy", linkStyle) { onPrivacyPolicy() }) { append("Privacy Policy") }
                    append(".")
                },
                Modifier.weight(1f).offset(x = (-13).dp),
                color = PantryColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        FormMessage(if (attempted) validation ?: submission.error else null)
        PrimaryCta("Create account", submit, enabled = agreed, loading = submission.loading)
        AccountPrompt("Already have an account?", "Log in", onNavigateToLogin)
        TextButton(onClick = onConfigureSharing) { Text("Sharing server") }
        Spacer(Modifier.height(32.dp))
        Spacer(Modifier.weight(1f))
        Text("A little planning today. A little less waste tomorrow.",
            color = PantryColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(name = "Create account · 412 × 892", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
private fun SignUpScreenPreview() = PantryPalTheme {
    SignUpScreen({}, {}, {}, {}, {}, { _, _, _ -> AuthResult.Success }, initialName = "Aisha Lim", initialEmail = "aisha.lim@email.com")
}

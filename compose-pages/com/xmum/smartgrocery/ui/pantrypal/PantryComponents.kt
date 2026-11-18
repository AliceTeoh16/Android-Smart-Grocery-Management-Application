package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Return Success only after the existing authentication/reset service has succeeded. */
sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val message: String) : AuthResult
}

@Stable
internal class FormSubmission {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var completed by mutableStateOf(false)
        private set

    fun submit(scope: CoroutineScope, action: suspend () -> AuthResult, onSuccess: () -> Unit = {}) {
        if (loading) return
        error = null
        completed = false
        loading = true
        scope.launch {
            try {
                when (val result = action()) {
                    AuthResult.Success -> { completed = true; onSuccess() }
                    is AuthResult.Failure -> error = result.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                loading = false
            }
        }
    }
}

/**
 * Uses real system insets; screenshot status icons and gesture bars are OS chrome.
 * Minimum viewport height anchors footer cards, while scrolling supports small screens and IME.
 */
@Composable
internal fun PantryPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize(), color = PantryColors.Cream) {
        BoxWithConstraints(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val viewportHeight = maxHeight
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun AuthHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).offset(x = (-14).dp)) {
            Icon(PantryIcons.ArrowLeft, contentDescription = "Go back", tint = PantryColors.Green, modifier = Modifier.size(22.dp))
        }
        Text(title, Modifier.offset(x = (-14).dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(
        text, modifier = Modifier.background(PantryColors.Pastel, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = PantryColors.Green, style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
internal fun IconTile(icon: ImageVector, large: Boolean = false) {
    Box(
        Modifier.size(if (large) 92.dp else 64.dp)
            .background(PantryColors.Pastel, RoundedCornerShape(if (large) 30.dp else 22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(if (large) 40.dp else 30.dp), tint = PantryColors.Green)
    }
}

@Composable
internal fun PantryField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = label },
            singleLine = true, shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text(placeholder, color = PantryColors.Muted, style = MaterialTheme.typography.bodyMedium) },
            visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = !password && keyboardType == KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            trailingIcon = {
                if (password) {
                    IconButton(onClick = { visible = !visible }, enabled = enabled) {
                        Icon(
                            if (visible) PantryIcons.Eye else PantryIcons.EyeOff,
                            if (visible) "Hide password" else "Show password",
                            Modifier.size(22.dp), tint = PantryColors.Muted,
                        )
                    }
                } else Icon(icon, null, Modifier.size(22.dp), tint = PantryColors.Muted)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                disabledContainerColor = Color.White,
                focusedBorderColor = PantryColors.Green, unfocusedBorderColor = PantryColors.Border,
                cursorColor = PantryColors.Green,
            ),
        )
    }
}

@Composable
internal fun PrimaryCta(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PantryColors.Orange, contentColor = Color.White,
            disabledContainerColor = PantryColors.Orange.copy(alpha = 0.55f), disabledContentColor = Color.White,
        ),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(PantryIcons.ArrowRight, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun AccountPrompt(prefix: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prefix, color = PantryColors.Muted, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick, contentPadding = PaddingValues(horizontal = 5.dp)) {
            Text(action, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun InfoCard(icon: ImageVector, text: String, title: String? = null) {
    Surface(color = PantryColors.Pastel, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = if (title == null) Alignment.CenterVertically else Alignment.Top) {
            Icon(icon, null, Modifier.size(22.dp), tint = PantryColors.Green)
            Spacer(Modifier.width(12.dp))
            Column {
                if (title != null) {
                    Text(title, color = PantryColors.Green, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                }
                Text(text, color = if (title == null) PantryColors.Green else PantryColors.Muted,
                    style = if (title == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun FormMessage(message: String?, isError: Boolean = true) {
    if (!message.isNullOrBlank()) {
        Text(
            message, Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = if (isError) MaterialTheme.colorScheme.error else PantryColors.Green,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun validEmail(email: String): Boolean = android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

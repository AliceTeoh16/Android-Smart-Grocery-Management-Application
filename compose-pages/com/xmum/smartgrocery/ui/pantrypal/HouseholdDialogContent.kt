package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HouseholdManagementMode { Overview, Details, Invite }

@Composable
fun HouseholdManagementSheetContent(
    householdName: String, householdDescription: String, isOwner: Boolean, loading: Boolean, available: Boolean,
    mode: HouseholdManagementMode, name: String, description: String, email: String,
    busy: Boolean, error: String?, success: String?, onModeChange: (HouseholdManagementMode) -> Unit,
    onNameChange: (String) -> Unit, onDescriptionChange: (String) -> Unit, onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit, onInviteCode: () -> Unit, onChooseHousehold: () -> Unit, onClose: () -> Unit,
) {
    val overview = mode == HouseholdManagementMode.Overview
    val editing = mode == HouseholdManagementMode.Details
    HouseholdSheetFrame(
        title = if (overview) "Manage household" else if (editing) "Household details" else "Invite an account",
        subtitle = if (overview) "A shared space for the everyday essentials."
            else if (editing) "Give your shared pantry a name that feels like home."
            else "Invite an existing account to your household.",
        busy = busy, onClose = onClose,
        onBack = if (overview) null else ({ onModeChange(HouseholdManagementMode.Overview) }),
    ) {
        if (loading) HouseholdSheetLoading()
        else if (!available) HouseholdSheetMessage("This household is no longer selected. Close this sheet and choose your household again.")
        else if (overview) {
            HouseholdSummary(householdName, householdDescription.ifBlank { "Shared pantry, shopping & budget" },
                if (isOwner) "Owner" else "Member")
            Spacer(Modifier.height(22.dp))
            Text("HOUSEHOLD ACTIONS", style = MaterialTheme.typography.labelSmall,
                color = PantryColors.Muted, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (isOwner) {
                HouseholdMenuAction("Edit household details", "Update the name and description", PantryIcons.Home,
                    { onModeChange(HouseholdManagementMode.Details) })
                Spacer(Modifier.height(10.dp))
                HouseholdMenuAction("Invite an account", "Add someone who already has an account", PantryIcons.Mail,
                    { onModeChange(HouseholdManagementMode.Invite) })
                Spacer(Modifier.height(10.dp))
            }
            HouseholdMenuAction("Choose or join household", "Switch between personal and shared spaces", PantryIcons.People,
                onChooseHousehold)
            Spacer(Modifier.height(22.dp))
            HouseholdPrimaryAction("View invite code", PantryIcons.Share, true, onInviteCode)
        } else if (!isOwner) {
            HouseholdSheetMessage("Only the household owner can edit details or invite accounts.")
        } else {
            if (editing) {
                HouseholdTextField(name, onNameChange, "Household name", "e.g. Our home", PantryIcons.Home, !busy,
                    error != null, KeyboardType.Text)
                Spacer(Modifier.height(14.dp))
                HouseholdTextField(description, onDescriptionChange, "Description (optional)",
                    "A little about your household", null, !busy, false, KeyboardType.Text, multiline = true)
            } else {
                HouseholdSummary(householdName, "Pantry, shopping list & budget", null)
                Spacer(Modifier.height(20.dp))
                HouseholdTextField(email, onEmailChange, "Account email", "name@example.com", PantryIcons.Mail,
                    !busy, error != null, KeyboardType.Email)
                Text("An invitation is saved for this account. Share your household code so they can join.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
            }
            Spacer(Modifier.height(22.dp))
            HouseholdPrimaryAction(if (busy) "Please wait…" else if (editing) "Save changes" else "Invite account",
                if (editing) PantryIcons.Check else PantryIcons.People, !busy, onSubmit)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onModeChange(HouseholdManagementMode.Overview) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = PantryColors.Green)
        if (error != null) Text(error, Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (success != null) HouseholdSheetMessage(success, Modifier.padding(top = 14.dp))
    }
}

@Composable
fun HouseholdInvitationSheetContent(
    householdName: String, inviteCode: String, loading: Boolean, available: Boolean, canShare: Boolean,
    copied: Boolean, onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit,
) {
    HouseholdSheetFrame("Invite someone home", "A shared pantry. A little more together.", false, onClose) {
        if (loading) HouseholdSheetLoading()
        else if (!available) HouseholdSheetMessage("This household is no longer selected. Close this sheet and choose your household again.")
        else {
            HouseholdSummary(householdName, "Share your pantry, shopping list & budget", null)
            Spacer(Modifier.height(20.dp))
            Surface(color = Color.White, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, PantryColors.Border)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("HOUSEHOLD CODE", style = MaterialTheme.typography.labelSmall,
                        color = PantryColors.Muted, fontWeight = FontWeight.SemiBold)
                    Text(inviteCode, Modifier.padding(top = 8.dp, bottom = 12.dp),
                        color = PantryColors.Green, fontSize = 36.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = onCopy, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp), border = BorderStroke(1.dp, PantryColors.Border)) {
                        Icon(if (copied) PantryIcons.Check else PantryIcons.Copy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (copied) "Copied" else "Copy code", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            InvitationStep("1", "Open ShelfWise and sign in.")
            Spacer(Modifier.height(10.dp))
            InvitationStep("2", "Choose Join household and enter this code.")
            Text("Use the same sharing server on both devices.", Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
            Spacer(Modifier.height(22.dp))
            HouseholdPrimaryAction("Share invitation link", PantryIcons.Share, canShare, onShare)
            if (!canShare) HouseholdSheetMessage("Enable cross-device sharing in Share & Connect before sharing this household.",
                Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PantryIcons.Shield, null, Modifier.size(20.dp), tint = PantryColors.Green)
                Text("Everyone’s personal space stays private.", Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
            }
        }
    }
}

@Composable
private fun HouseholdSheetFrame(title: String, subtitle: String, busy: Boolean, onClose: () -> Unit,
    onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = PantryColors.Cream, contentColor = PantryColors.Ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.88f)
            .imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(PantryColors.Border, CircleShape))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack, enabled = !busy) {
                    Icon(PantryIcons.ArrowLeft, "Back", tint = PantryColors.Green)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose, enabled = !busy) { Icon(PantryIcons.Close, "Close", tint = PantryColors.Muted) }
            }
            Text(subtitle, Modifier.padding(top = 4.dp, bottom = 24.dp),
                style = MaterialTheme.typography.bodyMedium, color = PantryColors.Muted)
            content()
        }
    }
}

@Composable
private fun HouseholdSummary(name: String, detail: String, role: String?) {
    Surface(color = PantryColors.Pastel, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(PantryColors.Pale, CircleShape), contentAlignment = Alignment.Center) {
                Icon(PantryIcons.Home, null, Modifier.size(24.dp), tint = PantryColors.Green)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(detail, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = PantryColors.Green)
                if (role != null) Text(role, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = PantryColors.Green, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HouseholdMenuAction(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = Color.White,
        border = BorderStroke(1.dp, PantryColors.Border)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = PantryColors.Green)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = PantryColors.Ink)
                Text(subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
            }
            Icon(PantryIcons.ArrowRight, null, Modifier.size(18.dp), tint = PantryColors.Muted)
        }
    }
}

@Composable
private fun HouseholdTextField(value: String, onValueChange: (String) -> Unit, label: String, hint: String,
    icon: ImageVector?, enabled: Boolean, isError: Boolean, keyboardType: KeyboardType, multiline: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onValueChange, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 5 else 1, label = { Text(label) }, placeholder = { Text(hint) },
        leadingIcon = if (icon == null) null else ({ Icon(icon, null) }),
        shape = RoundedCornerShape(14.dp), isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType,
            capitalization = if (keyboardType == KeyboardType.Email) KeyboardCapitalization.None else KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White, disabledContainerColor = PantryColors.Pale,
            unfocusedBorderColor = PantryColors.Border))
}

@Composable
private fun HouseholdPrimaryAction(title: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange,
            contentColor = Color.White)) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun InvitationStep(number: String, instruction: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(PantryColors.Pastel, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = PantryColors.Green)
        }
        Text(instruction, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = PantryColors.Ink)
    }
}

@Composable
private fun HouseholdSheetMessage(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = PantryColors.Pale,
        shape = RoundedCornerShape(14.dp)) {
        Text(message, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall,
            color = PantryColors.Green)
    }
}

@Composable
private fun HouseholdSheetLoading() {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = PantryColors.Green)
    }
}

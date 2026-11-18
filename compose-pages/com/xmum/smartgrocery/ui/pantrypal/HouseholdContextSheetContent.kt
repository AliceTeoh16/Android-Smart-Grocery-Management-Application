package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

data class HouseholdContextOption(val id: String, val name: String)
enum class HouseholdContextMode { Choose, Create, Join }

@Composable
fun HouseholdContextSheetContent(
    households: List<HouseholdContextOption>, selectedHouseholdId: String?, managedHouseholdName: String?,
    loading: Boolean, busy: Boolean, error: String?, mode: HouseholdContextMode, value: String,
    onValueChange: (String) -> Unit, onModeChange: (HouseholdContextMode) -> Unit,
    onSelect: (String?) -> Unit, onSubmit: () -> Unit, onInvite: () -> Unit,
    onLeave: () -> Unit, onClose: () -> Unit,
) {
    val choosing = mode == HouseholdContextMode.Choose
    val creating = mode == HouseholdContextMode.Create
    val title = if (choosing) "Your pantry space" else if (creating) "Create household" else "Join household"
    Surface(color = PantryColors.Cream, contentColor = PantryColors.Ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.88f)
            .imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp)
                .background(PantryColors.Border, CircleShape))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!choosing) IconButton(onClick = { onModeChange(HouseholdContextMode.Choose) }, enabled = !busy) {
                    Icon(PantryIcons.ArrowLeft, "Back", tint = PantryColors.Green)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose, enabled = !busy) {
                    Icon(PantryIcons.Close, "Close", tint = PantryColors.Muted)
                }
            }
            Text(if (choosing) "Choose a space for your pantry, shopping list and budget."
                else if (creating) "Make a shared space for the people you shop and cook with."
                else "Enter the 6-digit invite code from your household.",
                style = MaterialTheme.typography.bodyMedium, color = PantryColors.Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))

            if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = PantryColors.Green)
            } else if (choosing) {
                ContextOptionCard("Personal", "Only you can see your items and budget.", PantryIcons.Person,
                    selectedHouseholdId == null, !busy, { onSelect(null) })
                Spacer(Modifier.height(22.dp))
                Text("YOUR HOUSEHOLDS", style = MaterialTheme.typography.labelSmall,
                    color = PantryColors.Muted, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                if (households.isEmpty()) {
                    Surface(color = PantryColors.Pale, shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(PantryIcons.Home, null, Modifier.size(28.dp), tint = PantryColors.Green)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text("A little better, together", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text("Create or join a household to share the everyday essentials.",
                                    style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                } else households.forEach { home ->
                    ContextOptionCard(home.name, "Shared pantry, shopping & budget", PantryIcons.Home,
                        selectedHouseholdId == home.id, !busy, { onSelect(home.id) })
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(20.dp))
                ContextAction("Create household", PantryIcons.Plus, !busy,
                    { onModeChange(HouseholdContextMode.Create) })
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onModeChange(HouseholdContextMode.Join) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, PantryColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PantryColors.Green)) {
                    Icon(PantryIcons.People, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Join household", style = MaterialTheme.typography.labelLarge)
                }
                if (managedHouseholdName != null) {
                    HorizontalDivider(Modifier.padding(top = 22.dp, bottom = 14.dp), color = PantryColors.Border)
                    Text(managedHouseholdName, style = MaterialTheme.typography.labelMedium, color = PantryColors.Muted)
                    TextButton(onClick = onInvite, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(PantryIcons.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Invite / view join code")
                    }
                    TextButton(onClick = onLeave, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Leave household")
                    }
                }
            } else {
                OutlinedTextField(value = value, onValueChange = onValueChange, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                    label = { Text(if (creating) "Household name" else "Invite code") },
                    placeholder = { Text(if (creating) "e.g. Our home" else "6-digit code") },
                    leadingIcon = { Icon(if (creating) PantryIcons.Home else PantryIcons.People, null) },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (creating) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                        keyboardType = if (creating) KeyboardType.Text else KeyboardType.Number,
                        imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy) onSubmit() }),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White, disabledContainerColor = PantryColors.Pale,
                        unfocusedBorderColor = PantryColors.Border))
                Spacer(Modifier.height(20.dp))
                ContextAction(if (busy) "Please wait…" else title, if (creating) PantryIcons.Plus else PantryIcons.People,
                    !busy, onSubmit)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PantryIcons.Shield, null, Modifier.size(18.dp), tint = PantryColors.Green)
                    Text("Your personal space stays private.", Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp), color = PantryColors.Green)
            if (error != null) Text(error, Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ContextOptionCard(title: String, subtitle: String, icon: ImageVector,
    selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = if (selected) PantryColors.Pastel else Color.White,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) PantryColors.Green else PantryColors.Border)) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(if (selected) Color.White.copy(alpha = 0.65f) else PantryColors.Pale,
                CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = PantryColors.Green)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (selected) Icon(PantryIcons.Check, null, Modifier.size(22.dp), tint = PantryColors.Green)
        }
    }
}

@Composable
private fun ContextAction(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange, contentColor = Color.White)) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

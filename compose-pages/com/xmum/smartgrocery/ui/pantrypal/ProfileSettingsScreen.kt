package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xmum.smartgrocery.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProfileSettingsScreen(
    displayName: String, email: String, initials: String, householdName: String,
    householdSubtitle: String, notificationsEnabled: Boolean, expiryWarningDays: Int,
    language: String, onBack: () -> Unit, onHousehold: () -> Unit, onPrivacyPolicy: () -> Unit,
    onSignOut: () -> Unit, onPurchaseHistory: () -> Unit, modifier: Modifier = Modifier,
) {
    FeatureScrollPage(modifier) {
        FeatureHeader("Profile & Settings", onBack, initials)
        Spacer(Modifier.height(8.dp))
        FeatureCard(color = PantryColors.Pastel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                    Text(initials.ifBlank { "?" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PantryColors.Green)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayName.ifBlank { "Your profile" }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(email, fontSize = 12.sp, color = PantryColors.Muted)
                    Text("Personal info · read only", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = PantryColors.Green)
                }
            }
        }
        SettingsHeading("Your household")
        Surface(onClick = onHousehold, shape = RoundedCornerShape(22.dp), color = Color.White) {
            SettingsRow(PantryIcons.People, householdName, detail = householdSubtitle, chevron = true)
        }
        SettingsHeading("Your activity")
        Surface(onClick = onPurchaseHistory, shape = RoundedCornerShape(22.dp), color = Color.White) {
            SettingsRow(PantryIcons.History, "Purchase history", detail = "Review your groceries and spending", chevron = true)
        }
        SettingsHeading("Notifications")
        FeatureCard {
            NotificationRow(PantryIcons.Bell, "Push notifications", "Shopping and household updates", notificationsEnabled)
            Spacer(Modifier.height(12.dp))
            NotificationRow(PantryIcons.History, "Expiry alerts", "$expiryWarningDays days before · Household setting", notificationsEnabled)
        }
        SettingsHeading("Preferences & support")
        FeatureCard {
            SettingsRow(PantryIcons.Globe, "Language", value = language, compact = true)
            SettingsRow(PantryIcons.Wallet, "Currency", value = "MYR · RM", compact = true)
            Surface(onClick = onPrivacyPolicy, color = Color.Transparent) {
                SettingsRow(PantryIcons.Shield, "Privacy & security", chevron = true, compact = true)
            }
            SettingsRow(PantryIcons.Help, "Help & feedback", value = "Unavailable", compact = true)
        }
        Spacer(Modifier.height(12.dp))
        FeatureAction("Sign out", onSignOut, icon = PantryIcons.Logout, color = PantryColors.Pastel, contentColor = PantryColors.Green)
        Text(stringResource(R.string.app_profile_footer, stringResource(R.string.app_name)), Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
            fontSize = 10.sp, color = PantryColors.Muted)
        Text("Notification status, language, and currency are read only here.", Modifier.padding(top = 10.dp),
            fontSize = 10.sp, color = PantryColors.Muted)
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(text, Modifier.padding(vertical = 14.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, detail: String? = null, value: String? = null,
    chevron: Boolean = false, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(if (compact) 0.dp else 16.dp).heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(PantryColors.Pale, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = PantryColors.Green)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, fontSize = 12.sp, color = PantryColors.Muted)
        }
        if (value != null) Text(value, fontSize = 12.sp, color = PantryColors.Green)
        if (chevron) Icon(PantryIcons.ChevronDown, "Open $title", Modifier.size(20.dp).rotate(-90f), tint = PantryColors.Muted)
    }
}

@Composable
private fun NotificationRow(icon: ImageVector, title: String, detail: String, checked: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(23.dp), tint = PantryColors.Green)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, Modifier.padding(top = 5.dp), fontSize = 11.sp, color = PantryColors.Muted)
        }
        Switch(checked, onCheckedChange = null, enabled = false,
            colors = SwitchDefaults.colors(disabledCheckedTrackColor = PantryColors.Green,
                disabledCheckedThumbColor = Color.White, disabledUncheckedTrackColor = PantryColors.Border))
    }
}

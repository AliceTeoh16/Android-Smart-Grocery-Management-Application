package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PantryMemberUi(val name: String, val role: String, val detail: String, val initials: String,
    val id: String = name, val status: String = "Active")

@Composable
fun ShareAndConnectScreen(
    householdName: String, members: List<PantryMemberUi>, inviteCode: String, inviteLink: String,
    expiryWarning: String, canDecrease: Boolean, canIncrease: Boolean,
    onDecrement: () -> Unit, onIncrement: () -> Unit, onCopyCode: () -> Unit,
    onShareInvite: () -> Unit, onManage: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier, initials: String? = null, householdDescription: String = "",
    hasHousehold: Boolean = inviteCode.isNotBlank(),
    sharingContent: (@Composable () -> Unit)? = null,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { FeatureHeader("Share & Connect", onBack, initials) }
        if (sharingContent != null) item { sharingContent() }
        item {
            FeatureCard(color = PantryColors.Pastel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp).background(PantryColors.Pale, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(PantryIcons.Home, null, Modifier.size(28.dp), tint = PantryColors.Green)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(householdName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PantryColors.Ink)
                        if (hasHousehold) Text("${members.size} ${if (members.size == 1) "person" else "people"} in this pantry", fontSize = 12.sp, color = PantryColors.Green)
                    }
                }
                if (householdDescription.isNotBlank()) Text(householdDescription, Modifier.padding(top = 12.dp), fontSize = 12.sp, color = PantryColors.Green)
                if (!hasHousehold) {
                    Spacer(Modifier.height(12.dp))
                    FeatureAction("Create or join a household", onManage, icon = PantryIcons.People)
                }
            }
        }
        if (hasHousehold) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your pantry people", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    TextButton(onClick = onManage) { Text("Manage", fontSize = 12.sp) }
                }
            }
            items(members, key = { it.id }) { member ->
                FeatureCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(PantryColors.Pastel, CircleShape), contentAlignment = Alignment.Center) {
                            Text(member.initials, color = PantryColors.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(member.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(member.detail, fontSize = 11.sp, color = PantryColors.Muted)
                        }
                        FeatureBadge(member.role,
                            if (member.role == "Owner") Color(0xFFE9E1F6) else PantryColors.Pastel,
                            if (member.role == "Owner") Color(0xFF69557D) else PantryColors.Green)
                    }
                }
            }
            item { Text("Invite someone home", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            item {
                FeatureCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HOUSEHOLD CODE", fontSize = 11.sp, color = PantryColors.Muted)
                            Text(inviteCode, color = PantryColors.Green, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = onCopyCode) { Icon(PantryIcons.Copy, "Copy household code", tint = PantryColors.Green) }
                    }
                    Text(inviteLink, Modifier.padding(top = 8.dp, bottom = 12.dp), fontSize = 11.sp, color = PantryColors.Muted)
                    FeatureAction("Share invitation link", onShareInvite, icon = PantryIcons.Share)
                }
            }
            item {
                FeatureCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(PantryIcons.Bell, null, Modifier.size(24.dp), tint = PantryColors.Green)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Expiry warning", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Remind the household before food expires.", fontSize = 11.sp, color = PantryColors.Muted)
                        }
                        Row(Modifier.background(PantryColors.Pale, CircleShape), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDecrement, enabled = canDecrease, modifier = Modifier.size(36.dp)) {
                                Text("−", fontSize = 20.sp)
                            }
                            Text(expiryWarning, fontSize = 13.sp, color = PantryColors.Green, fontWeight = FontWeight.Bold)
                            IconButton(onClick = onIncrement, enabled = canIncrease, modifier = Modifier.size(36.dp)) {
                                Text("+", fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ShoppingListScreen(
    initials: String, householdName: String, householdVisible: Boolean, memberCount: Int,
    cartTotal: String, remainingBudget: String, budgetStatus: String,
    filterLabels: List<String>, selectedFilter: Int, summary: String, hasItems: Boolean,
    canClear: Boolean, onFilterSelected: (Int) -> Unit,
    onHome: () -> Unit, onProfile: () -> Unit, onHousehold: () -> Unit, onPurchaseHistory: () -> Unit,
    onAddItem: () -> Unit, onVoice: () -> Unit, onClearChecked: () -> Unit,
    listContent: @Composable () -> Unit, modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        FeatureScrollPage {
            FeatureTopBar("Shopping List", onHome, initials, onProfile)
            PurchaseHistoryShortcut(onPurchaseHistory)
            Spacer(Modifier.height(8.dp))
            if (householdVisible) {
                Surface(onClick = onHousehold, shape = RoundedCornerShape(14.dp), color = PantryColors.Pastel) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PantryIcons.People, null, Modifier.size(22.dp), tint = PantryColors.Green)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(householdName, fontSize = 14.sp, color = PantryColors.Green, fontWeight = FontWeight.SemiBold)
                            Text(if (memberCount == 0) "Create or join a household" else "$memberCount ${if (memberCount == 1) "member" else "members"} in this household",
                                fontSize = 11.sp, color = PantryColors.Muted)
                        }
                        Icon(PantryIcons.Share, "Share & Connect", Modifier.size(20.dp), tint = PantryColors.Green)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            FeatureCard(color = PantryColors.Green) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Planned cart total", color = PantryColors.Pastel, fontSize = 12.sp)
                        Text(cartTotal, Modifier.padding(top = 4.dp), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                    Icon(PantryIcons.Basket, null, Modifier.size(32.dp), tint = PantryColors.Pastel)
                }
                Text("$remainingBudget available", Modifier.padding(top = 8.dp), fontSize = 11.sp, color = PantryColors.Pastel)
                Text("Unpurchased estimate · not counted as spent yet", Modifier.padding(top = 6.dp), fontSize = 10.sp, color = PantryColors.Pastel)
                if (budgetStatus.startsWith("Warning")) {
                    Text(budgetStatus, Modifier.padding(top = 8.dp), fontSize = 11.sp, color = Color(0xFFFFD8C7))
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filterLabels.forEachIndexed { index, label ->
                    Surface(onClick = { onFilterSelected(index) }, shape = RoundedCornerShape(20.dp),
                        color = if (index == selectedFilter) Color(0xFFE9E1F6) else PantryColors.Pastel) {
                        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold, color = if (index == selectedFilter) Color(0xFF69557D) else PantryColors.Green)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("To buy", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            FeatureCard {
                if (hasItems) listContent() else {
                    Text("Your list is clear", fontWeight = FontWeight.Bold)
                    Text("Add an item to start your next shop.", Modifier.padding(top = 8.dp), fontSize = 13.sp, color = PantryColors.Muted)
                }
            }
            Text(summary, Modifier.padding(top = 12.dp, bottom = 12.dp), fontSize = 11.sp, color = PantryColors.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClearChecked, enabled = canClear) { Text("Clear purchased", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(64.dp))
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = PantryColors.Cream) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                VoiceCommandCard("Tap mic to add items by voice", onVoice)
                Spacer(Modifier.height(8.dp))
                FeatureAction("Add an item", onAddItem, icon = PantryIcons.Plus)
            }
        }
    }
}

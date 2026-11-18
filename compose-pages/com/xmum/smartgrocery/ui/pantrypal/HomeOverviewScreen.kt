package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xmum.smartgrocery.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PantryAction { Profile, Household, Expiring, LowStock, Inventory, AiChecklist, Shopping, Budget, AllActivity, PurchaseHistory }
enum class ActivityKind { Milk, Completed, Inventory, Shopping }

@Immutable
data class PantryActivity(val id: String, val title: String, val detail: String, val result: String, val kind: ActivityKind)

/** Formatted values come from the host, including localized currency and the user's date. */
@Immutable
data class HomeOverviewUiState(
    val displayName: String,
    val initials: String,
    val dateLabel: String,
    val householdName: String,
    val memberCount: Int,
    val expiringCount: Int,
    val expiringSummary: String,
    val lowStockCount: Int,
    val lowStockSummary: String,
    val budgetRemainingLabel: String,
    val activity: List<PantryActivity>,
    val greeting: String = "Good morning",
    val budgetSubtitle: String = "Left this month · View →",
    val isLoading: Boolean = false,
    val dataError: String? = null,
) {
    // Synced Room updates copy this state; derive the label from the latest count each time.
    val householdSubtitle: String
        get() = "Shared with $memberCount ${if (memberCount == 1) "member" else "members"}"
}

@Composable
fun HomeOverviewScreen(
    state: HomeOverviewUiState,
    onAction: (PantryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    PantryPage(modifier) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PantryIcons.Home, null, Modifier.size(22.dp), tint = PantryColors.Green)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onAction(PantryAction.Profile) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Open profile" }) {
                Box(Modifier.size(35.dp).background(PantryColors.Pastel, CircleShape), contentAlignment = Alignment.Center) {
                    Text(state.initials, style = MaterialTheme.typography.labelMedium, color = PantryColors.Green)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(state.dateLabel, color = PantryColors.Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.greeting}, ${state.displayName}", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(8.dp))
            Icon(PantryIcons.Sun, null, Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("Let’s make the most of your pantry.", color = PantryColors.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        if (state.isLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = PantryColors.Green)
            Spacer(Modifier.height(12.dp))
        }
        FormMessage(state.dataError)
        HouseholdCard(state, onClick = { onAction(PantryAction.Household) })
        Spacer(Modifier.height(12.dp))
        ExpiringCard(state, onClick = { onAction(PantryAction.Expiring) })
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OverviewMetricCard(
                title = "${state.lowStockCount} low stock", detail = "${state.lowStockSummary} · Restock →",
                icon = PantryIcons.Box, background = PantryColors.Peach, detailColor = PantryColors.Orange,
                onClick = { onAction(PantryAction.LowStock) }, modifier = Modifier.weight(1f),
            )
            OverviewMetricCard(
                title = state.budgetRemainingLabel, detail = state.budgetSubtitle,
                icon = PantryIcons.Wallet, background = PantryColors.Pastel, detailColor = PantryColors.Green,
                onClick = { onAction(PantryAction.Budget) }, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Your pantry toolkit", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolkitShortcut("Inventory", PantryIcons.Fridge, Modifier.weight(1f)) { onAction(PantryAction.Inventory) }
            ToolkitShortcut("AI Checklist", PantryIcons.Sparkles, Modifier.weight(1f)) { onAction(PantryAction.AiChecklist) }
            ToolkitShortcut("Shopping", PantryIcons.Basket, Modifier.weight(1f)) { onAction(PantryAction.Shopping) }
            ToolkitShortcut("Budget", PantryIcons.Pie, Modifier.weight(1f)) { onAction(PantryAction.Budget) }
        }
        Spacer(Modifier.height(12.dp))
        Surface(onClick = { onAction(PantryAction.PurchaseHistory) }, shape = RoundedCornerShape(20.dp), color = Color.White) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PantryIcons.History, null, Modifier.size(24.dp), tint = PantryColors.Green)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Purchase history", style = MaterialTheme.typography.labelLarge, color = PantryColors.Green)
                    Text("Look back at your groceries and spending", style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
                }
                Icon(PantryIcons.ArrowRight, null, Modifier.size(18.dp), tint = PantryColors.Green)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Around your pantry", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onAction(PantryAction.AllActivity) }, contentPadding = PaddingValues(start = 12.dp)) {
                Text("View all", style = MaterialTheme.typography.labelMedium)
            }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(24.dp), shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.activity.isEmpty()) Text("Your pantry’s story starts here.", color = PantryColors.Muted, style = MaterialTheme.typography.bodyMedium)
                state.activity.take(2).forEach { ActivityRow(it) }
            }
        }
    }
}

@Composable
private fun HouseholdCard(state: HomeOverviewUiState, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = PantryColors.Pastel) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PantryIcons.People, null, Modifier.size(21.dp), tint = PantryColors.Green)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(state.householdName, color = PantryColors.Green, style = MaterialTheme.typography.labelLarge)
                Text(state.householdSubtitle,
                    color = PantryColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(PantryIcons.Share, "Manage shared pantry", Modifier.size(20.dp), tint = PantryColors.Green)
        }
    }
}

@Composable
private fun ExpiringCard(state: HomeOverviewUiState, onClick: () -> Unit) {
    Surface(onClick = onClick, color = PantryColors.Green, contentColor = Color.White,
        shape = RoundedCornerShape(24.dp), shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("A LITTLE HEADS-UP", color = PantryColors.Pastel, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                Text(if (state.expiringCount == 0) "No items expiring soon." else
                    "${state.expiringCount} ${if (state.expiringCount == 1) "item needs" else "items need"} a little love.",
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(state.expiringSummary, color = PantryColors.Pastel, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("See what’s expiring", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Icon(PantryIcons.ArrowRight, null, Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(PantryIcons.Leaf, null, Modifier.size(44.dp), tint = Color(0xFFD9E5B4))
        }
    }
}

@Composable
private fun OverviewMetricCard(
    title: String, detail: String, icon: ImageVector, background: Color, detailColor: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp), color = background, shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = PantryColors.Green)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(detail, color = detailColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToolkitShortcut(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(24.dp), tint = PantryColors.Green)
            Spacer(Modifier.height(6.dp))
            Text(label, color = PantryColors.Green, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ActivityRow(activity: PantryActivity) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(PantryColors.Pale, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(when (activity.kind) {
                ActivityKind.Milk -> PantryIcons.Milk
                ActivityKind.Completed -> PantryIcons.Check
                ActivityKind.Inventory -> PantryIcons.Box
                ActivityKind.Shopping -> PantryIcons.Basket
            },
                null, Modifier.size(22.dp), tint = PantryColors.Green)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(activity.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(activity.detail, color = PantryColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Text(activity.result, color = PantryColors.Green, style = MaterialTheme.typography.labelMedium)
    }
}

/** Screenshot data for previews/demos only; pass real state from the host at runtime. */
val PantryHomePreviewState = HomeOverviewUiState(
    displayName = "Aisha", initials = "AL", dateLabel = "FRIDAY, 16 OCTOBER",
    householdName = "Home Pantry 3", memberCount = 3, expiringCount = 2,
    expiringSummary = "Use milk & beef in the next 3 days.", lowStockCount = 2,
    lowStockSummary = "Beef & eggs", budgetRemainingLabel = "RM257.70",
    activity = listOf(
        PantryActivity("milk", "Daniel added fresh milk", "1 litre · Fridge · 12 min ago", "+1", ActivityKind.Milk),
        PantryActivity("shopping", "Mei picked up the vegetables", "Shopping list updated · 1 hour ago", "Done", ActivityKind.Completed),
    ),
)

@Preview(name = "Home overview · 412 × 892", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
private fun HomeOverviewScreenPreview() = PantryPalTheme { HomeOverviewScreen(PantryHomePreviewState, {}) }

package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BudgetTrackerScreen(
    initials: String, rangeLabels: List<String>, selectedRange: Int, onRangeSelected: (Int) -> Unit,
    percentLabel: String, progress: Float, remaining: String, remainingLabel: String,
    limit: String, spent: String, spentLabel: String, status: String, statusColor: Color,
    limitInput: String, limitError: String?, onLimitChange: (String) -> Unit, onSetLimit: () -> Unit,
    onHome: () -> Unit, onProfile: () -> Unit, onPurchaseHistory: () -> Unit, chartContent: @Composable () -> Unit,
    breakdownContent: @Composable () -> Unit, modifier: Modifier = Modifier,
    contextName: String = "Personal (private)", onContext: () -> Unit = {},
    selectedScope: Int = 0, onScopeSelected: (Int) -> Unit = {},
) {
    FeatureScrollPage(modifier) {
        FeatureTopBar("Budget Tracker", onHome, initials, onProfile)
        PurchaseHistoryShortcut(onPurchaseHistory)
        ScopeFilterBar(selectedScope, onScopeSelected)
        TextButton(onClick = onContext) { Text(contextName + " · Change context") }
        Spacer(Modifier.height(8.dp))
        FeatureCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rangeLabels.forEachIndexed { index, label ->
                    Surface(onClick = { onRangeSelected(index) }, shape = RoundedCornerShape(20.dp),
                        color = if (index == selectedRange) PantryColors.Green else PantryColors.Pale) {
                        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 12.sp,
                            color = if (index == selectedRange) Color.White else PantryColors.Muted)
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, Modifier.fillMaxSize(), color = statusColor,
                        trackColor = PantryColors.Pastel, strokeWidth = 8.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(percentLabel, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PantryColors.Green)
                        Text("used", fontSize = 10.sp, color = PantryColors.Muted)
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(remainingLabel, fontSize = 12.sp, color = PantryColors.Muted)
                    Text(remaining, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = PantryColors.Ink)
                    FeatureBadge(status, foreground = statusColor)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BudgetMetric("Limit", limit, PantryIcons.Wallet, PantryColors.Pale, PantryColors.Ink, Modifier.weight(1f))
                BudgetMetric(spentLabel, spent, PantryIcons.Basket, Color(0xFFEFF9F3), statusColor, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        FeatureCard {
            Text("Category spending", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            chartContent()
        }
        Spacer(Modifier.height(14.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PantryIcons.Wallet, null, Modifier.size(18.dp), tint = PantryColors.Muted)
                    TextField(limitInput, onLimitChange, Modifier.weight(1f), singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = { Text("Monthly Budget Limit…", fontSize = 13.sp, color = PantryColors.Muted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = limitError != null,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    Button(onClick = onSetLimit, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange)) { Text("Set Limit", fontSize = 13.sp) }
                }
                if (limitError != null) Text(limitError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        Text("Spending by category", Modifier.padding(vertical = 14.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        FeatureCard { breakdownContent() }
    }
}

@Composable
private fun BudgetMetric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color, color: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = background) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp), tint = PantryColors.Green)
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 11.sp, color = PantryColors.Muted)
            }
            Text(value, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, color = color, fontSize = 19.sp)
        }
    }
}

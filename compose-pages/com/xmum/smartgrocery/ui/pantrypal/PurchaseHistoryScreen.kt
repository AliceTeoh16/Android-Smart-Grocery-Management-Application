package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmum.smartgrocery.ui.history.PurchaseHistoryRecord
import com.xmum.smartgrocery.ui.history.PurchaseHistoryState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PurchaseHistoryScreen(state: PurchaseHistoryState, onSearch: (String) -> Unit, onMonth: (Boolean) -> Unit,
    onScope: (Boolean) -> Unit, onContext: () -> Unit, onBack: () -> Unit) {
    val locale = Locale.getDefault()
    val dates = SimpleDateFormat("d MMM yyyy", locale)
    val groups = state.records.groupBy { dates.format(Date(it.timestamp)) }
    Surface(color = PantryColors.Cream, contentColor = PantryColors.Ink) {
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FeatureHeader("Purchase history", onBack)
                ScopeFilterBar(if (state.household) 1 else 0, { onScope(it == 1) })
                TextButton(onClick = onContext) { Text(state.contextName + " · Change context") }
            }
            item {
                FeatureCard(color = PantryColors.Pastel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(PantryIcons.History, null, Modifier.size(30.dp), tint = PantryColors.Green)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(if (state.thisMonth) "Recorded this month" else "Recorded total", color = PantryColors.Green,
                                style = MaterialTheme.typography.bodySmall)
                            Text(money(state.total), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PantryColors.Ink)
                            Text("${state.records.size} ${if (state.records.size == 1) "purchase record" else "purchase records"}" +
                                if (state.search.isNotBlank()) " matching your search" else "",
                                style = MaterialTheme.typography.bodySmall, color = PantryColors.Green)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(value = state.search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search purchases") }, placeholder = { Text("Item name or category") }, singleLine = true,
                    leadingIcon = { Icon(PantryIcons.Search, null) }, shape = RoundedCornerShape(14.dp),
                    trailingIcon = if (state.search.isEmpty()) null else ({ IconButton(onClick = { onSearch("") }) {
                        Icon(PantryIcons.Close, "Clear search")
                    } }), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White, unfocusedBorderColor = PantryColors.Border))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = !state.thisMonth, onClick = { onMonth(false) }, label = { Text("All time") })
                    FilterChip(selected = state.thisMonth, onClick = { onMonth(true) }, label = { Text("This month") })
                }
                Text("Includes recorded inventory additions and restocks. Manually added stock is included.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = PantryColors.Green) }
            if (state.error != null) item { Text(state.error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium) }
            if (!state.loading && state.error == null && state.records.isEmpty()) item {
                FeatureCard {
                    Icon(PantryIcons.Basket, null, Modifier.size(30.dp), tint = PantryColors.Green)
                    Text(if (state.search.isNotBlank() || state.thisMonth) "No matching purchases" else "No purchases yet",
                        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    Text(if (state.search.isNotBlank() || state.thisMonth) "Try another search or choose All time."
                        else "Groceries added to this space will appear here, even after they leave your pantry.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = PantryColors.Muted)
                }
            }
            groups.forEach { (date, records) ->
                item(key = "date-$date") { Text(date, Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = PantryColors.Green) }
                items(records, key = { it.id }) { record -> PurchaseHistoryCard(record) }
            }
        }
    }
}

@Composable
private fun PurchaseHistoryCard(record: PurchaseHistoryRecord) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, PantryColors.Border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(record.category, Modifier.background(PantryColors.Pale, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = PantryColors.Green)
            Text(record.name, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${record.quantity} ${record.unit} × ${money(record.unitPrice)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = PantryColors.Muted)
                Text(money(record.total), style = MaterialTheme.typography.labelLarge, color = PantryColors.Green)
            }
        }
    }
}

private fun money(value: Double): String = String.format(Locale.getDefault(), "RM %.2f", value)

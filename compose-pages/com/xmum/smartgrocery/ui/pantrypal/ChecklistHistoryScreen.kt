package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import com.xmum.smartgrocery.ui.recipes.ChecklistHistoryAdapter
import com.xmum.smartgrocery.ui.recipes.SavedChecklist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reuses the existing collector and adapter's committed lists, without a second flow subscription. */
@Composable
internal fun rememberHistoryItems(adapter: ChecklistHistoryAdapter): State<List<SavedChecklist>> {
    val items = remember(adapter) { mutableStateOf(adapter.currentList.toList()) }
    DisposableEffect(adapter) {
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun update() { items.value = adapter.currentList.toList() }
            override fun onChanged() = update()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = update()
        }
        adapter.registerAdapterDataObserver(observer)
        items.value = adapter.currentList.toList()
        onDispose { adapter.unregisterAdapterDataObserver(observer) }
    }
    return items
}

@Composable
fun ChecklistHistoryScreen(
    checklists: List<SavedChecklist>, onOpen: (SavedChecklist) -> Unit,
    onClose: () -> Unit, onClearAll: () -> Unit, modifier: Modifier = Modifier,
    isClearing: Boolean = false,
) {
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
        Column(modifier.fillMaxSize().background(PantryColors.Cream, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) {
            Column(Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 100.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).size(42.dp, 3.dp)
                    .background(PantryColors.Border, CircleShape))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PantryIcons.History, null, Modifier.size(24.dp), tint = Color(0xFF2E7D32))
                    Spacer(Modifier.width(12.dp))
                    Text("Checklist History", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    IconButton(onClick = onClose) { Icon(PantryIcons.Close, "Close history", tint = PantryColors.Muted) }
                }
                Surface(color = PantryColors.Pale, shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PantryIcons.Sparkles, null, Modifier.size(20.dp), tint = PantryColors.Green)
                        Spacer(Modifier.width(10.dp))
                        Text("Your 10 most recent checklists are saved automatically. Tap one to reopen its ingredients and cooking steps.",
                            fontSize = 11.sp, color = PantryColors.Muted, lineHeight = 16.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (checklists.isEmpty()) {
                    FeatureCard {
                        Icon(PantryIcons.History, null, Modifier.align(Alignment.CenterHorizontally).padding(12.dp).size(40.dp), tint = PantryColors.Green)
                        Text("No checklist history yet", Modifier.align(Alignment.CenterHorizontally), fontWeight = FontWeight.Bold)
                        Text("Your saved recipe checklists will appear here.", Modifier.padding(top = 8.dp), fontSize = 13.sp, color = PantryColors.Muted)
                    }
                }
                checklists.forEach { checklist ->
                    key(checklist.id) {
                        HistoryCard(checklist, onOpen, enabled = !isClearing)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showClearConfirmation = true }, enabled = checklists.isNotEmpty() && !isClearing,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = PantryColors.Green)) {
                    Text(if (isClearing) "Clearing…" else "Clear All History", fontSize = 12.sp)
                }
            }
        }
        if (showClearConfirmation) {
            AlertDialog(onDismissRequest = { showClearConfirmation = false },
                icon = { Icon(PantryIcons.Trash, null, tint = PantryColors.Orange) },
                title = { Text("Clear all history?", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to clear all history? This permanently removes all saved checklists.") },
                confirmButton = {
                    TextButton(onClick = { showClearConfirmation = false; onClearAll() }) {
                        Text("Clear history", color = PantryColors.Orange, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel", color = PantryColors.Green) }
                },
                containerColor = PantryColors.Cream, titleContentColor = PantryColors.Green, textContentColor = PantryColors.Ink)
        }
    }
}

@Composable
private fun HistoryCard(item: SavedChecklist, onOpen: (SavedChecklist) -> Unit, enabled: Boolean) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss.SSS", Locale.getDefault()) }
    val cooked = item.status == "cooked"
    val cancelled = item.status == "cancelled"
    val badgeColor = when { cooked -> PantryColors.Green; cancelled -> PantryColors.Muted; else -> Color(0xFF936600) }
    Surface(onClick = { onOpen(item) }, enabled = enabled, shape = RoundedCornerShape(22.dp), color = Color.White,
        shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Open ${item.dishName}, ${item.servings} servings, ${dateFormat.format(Date(item.createdAt))}"
        }) {
        Row(Modifier.padding(16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${item.dishName} · ${item.servings} serving(s)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(dateFormat.format(Date(item.createdAt)), Modifier.padding(top = 5.dp), fontSize = 10.sp, color = PantryColors.Muted)
            }
            Surface(shape = CircleShape, border = BorderStroke(1.dp, badgeColor),
                color = when { cooked -> PantryColors.Pastel; cancelled -> PantryColors.Pale; else -> Color(0xFFFFF0BD) }) {
                Text(when { cooked -> "Cooked"; cancelled -> "Cancelled"; else -> "Pending" },
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = badgeColor)
            }
            Icon(PantryIcons.ChevronDown, null, Modifier.size(20.dp).rotate(-90f), tint = PantryColors.Muted)
        }
    }
}

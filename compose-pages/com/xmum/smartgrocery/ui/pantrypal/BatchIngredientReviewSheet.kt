package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmum.smartgrocery.ui.quickadd.BatchIngredientDraft
import com.xmum.smartgrocery.ui.quickadd.IngredientScanState
import com.xmum.smartgrocery.utils.GroceryCategories
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BatchIngredientReviewSheet(
    state: IngredientScanState, onTakePhoto: () -> Unit, onChooseGallery: () -> Unit, onCancel: () -> Unit,
    onRename: (String, String) -> Unit, onCategory: (String, String) -> Unit,
    onQuantity: (String, Int) -> Unit, onSelect: (String, Boolean) -> Unit, onDelete: (String) -> Unit,
    onExpiry: (String) -> Unit, onAddItem: () -> Unit, onAddAll: () -> Unit,
    destinationReady: Boolean = true, destinationContent: @Composable () -> Unit = {},
) {
    Surface(color = PantryColors.Cream, contentColor = PantryColors.Ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f)
            .imePadding().navigationBarsPadding()) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).size(42.dp, 4.dp)
                .background(PantryColors.Border, RoundedCornerShape(2.dp)))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.reviewing) "Batch Review Checklist" else "Scan inventory items",
                    Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onCancel, enabled = !state.saving) {
                    Icon(PantryIcons.Close, "Close ingredient scanner", tint = PantryColors.Green)
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 100.dp)) {
                destinationContent()
                Spacer(Modifier.height(14.dp))
                if (state.reviewing) {
                    Text("${state.selectedCount} of ${state.items.size} selected · Review before adding",
                        fontSize = 13.sp, color = PantryColors.Green)
                    Text("Review categories, quantities, low-stock thresholds and optional expiry dates. Add anything that was missed.",
                        Modifier.padding(top = 6.dp, bottom = 14.dp), fontSize = 13.sp, color = PantryColors.Muted)
                    if (state.items.isEmpty()) Text("No items yet. Tap + Add Item to build your checklist.",
                        Modifier.padding(vertical = 20.dp), color = PantryColors.Muted)
                    state.items.forEach { item ->
                        key(item.id) {
                            BatchIngredientRow(item, state.saving, onRename, onCategory, onQuantity, onSelect, onDelete, onExpiry)
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    Text("Prices default to RM0.00. Non-food expiry defaults to Not Set. Tap any date to set or clear it.",
                        fontSize = 12.sp, color = PantryColors.Muted)
                } else {
                    FeatureCard(color = PantryColors.Pastel) {
                        Text("Skip the typing", fontWeight = FontWeight.SemiBold, color = PantryColors.Green)
                        Text("Take or choose a photo of food, household essentials or personal care products. The photo is sent to ShelfWise’s AI service to identify items by appearance, then you review the whole batch.",
                            fontSize = 14.sp, color = PantryColors.Green)
                    }
                    Spacer(Modifier.height(18.dp))
                    if (state.scanning) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp, color = PantryColors.Green)
                            Text("Identifying items…", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        FeatureAction("Take Photo", onTakePhoto, icon = PantryIcons.Camera)
                        Spacer(Modifier.height(10.dp))
                        FeatureAction("Choose from Gallery", onChooseGallery, color = PantryColors.Pastel,
                            contentColor = PantryColors.Green, icon = PantryIcons.Photo)
                    }
                }
                state.error?.let { message ->
                    Text(message, Modifier.padding(top = 14.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            Column(Modifier.fillMaxWidth().background(PantryColors.Cream).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                if (state.reviewing || state.error != null) {
                    TextButton(onClick = onAddItem, enabled = !state.saving && !state.scanning,
                        modifier = Modifier.fillMaxWidth()) { Text("+ Add Item", color = PantryColors.Green) }
                }
                if (state.reviewing) {
                    Button(onClick = onAddAll, enabled = state.canSave && destinationReady,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange, contentColor = Color.White)) {
                        if (state.saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (state.saving) "Adding items…" else "Add All to Inventory", fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.reviewing) "Cancel" else "Enter details manually", color = PantryColors.Green)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BatchIngredientRow(item: BatchIngredientDraft, saving: Boolean,
    onRename: (String, String) -> Unit, onCategory: (String, String) -> Unit, onQuantity: (String, Int) -> Unit,
    onSelect: (String, Boolean) -> Unit, onDelete: (String) -> Unit, onExpiry: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(item.id) {
        if (item.manuallyAdded && item.name.isBlank()) {
            focusRequester.requestFocus()
            bringIntoView.bringIntoView()
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.selected, onCheckedChange = { onSelect(item.id, it) }, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Select ${item.name.ifBlank { "new item" }}" })
                Text(if (item.selected) "Selected" else "Not selected", Modifier.weight(1f), fontSize = 12.sp, color = PantryColors.Muted)
                IconButton(onClick = { onDelete(item.id) }, enabled = !saving) {
                    Icon(PantryIcons.Trash, "Delete ${item.name.ifBlank { "new item" }}", tint = PantryColors.Green)
                }
            }
            OutlinedTextField(item.name, onValueChange = { onRename(item.id, it) }, enabled = !saving,
                label = { Text("Item name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    .focusRequester(focusRequester).bringIntoViewRequester(bringIntoView),
                isError = item.selected && item.name.isBlank(), shape = RoundedCornerShape(14.dp),
                supportingText = if (item.selected && item.name.isBlank()) ({ Text("Enter an item name") }) else null)
            Box(Modifier.padding(top = 10.dp)) {
                InventoryCategorySelector(item.category, { onCategory(item.id, it) }, !saving,
                    itemName = item.name.ifBlank { "new item" })
            }
            Text("Low-stock threshold: ${item.threshold}", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = PantryColors.Muted)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quantity", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (!item.quantityDetected) Text("Check count", fontSize = 11.sp, color = PantryColors.Muted)
                }
                Row(Modifier.background(PantryColors.Pale, RoundedCornerShape(16.dp)), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onQuantity(item.id, -1) }, enabled = !saving && item.quantity > 1) {
                        Text("−", fontSize = 23.sp, color = PantryColors.Green,
                            modifier = Modifier.semantics { contentDescription = "Decrease ${item.name}" })
                    }
                    Text(item.quantity.toString(), Modifier.widthIn(min = 28.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = PantryColors.Green)
                    IconButton(onClick = { onQuantity(item.id, 1) }, enabled = !saving && item.quantity < 999) {
                        Icon(PantryIcons.Plus, "Increase ${item.name}", Modifier.size(20.dp), tint = PantryColors.Green)
                    }
                }
            }
            Surface(onClick = { onExpiry(item.id) }, enabled = !saving, shape = RoundedCornerShape(14.dp),
                color = PantryColors.Pale, modifier = Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (item.expiryManuallySelected) "Expiry date (optional)" else "Suggested expiry (optional)", fontSize = 11.sp, color = PantryColors.Muted)
                        Text(com.xmum.smartgrocery.utils.DateUtils.formatDate(item.expiryDate),
                            fontSize = 14.sp, color = PantryColors.Green, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(PantryIcons.Calendar, "Expiry for ${item.name.ifBlank { "new item" }}", Modifier.size(20.dp), tint = PantryColors.Green)
                }
            }
        }
    }
}

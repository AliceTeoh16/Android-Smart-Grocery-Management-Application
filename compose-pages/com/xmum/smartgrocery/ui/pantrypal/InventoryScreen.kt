package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import com.xmum.smartgrocery.utils.Category
import com.xmum.smartgrocery.utils.FoodSubcategory

/** Observe the existing adapter's rendered rows; the inventory flow and its owner stay unchanged. */
@Composable
internal fun rememberInventoryRowCount(adapter: RecyclerView.Adapter<*>): State<Int> {
    val count = remember(adapter) { mutableIntStateOf(adapter.itemCount) }
    DisposableEffect(adapter) {
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun update() { count.intValue = adapter.itemCount }
            override fun onChanged() = update()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()
        }
        adapter.registerAdapterDataObserver(observer)
        count.intValue = adapter.itemCount
        onDispose { adapter.unregisterAdapterDataObserver(observer) }
    }
    return count
}

/** Layout only. The host supplies the original view bindings and action callbacks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(
    initials: String,
    scopeLabels: List<String>, selectedScope: Int, search: String, searchHint: String, filterLabels: List<String>,
    selectedFilter: Int, itemCount: Int, showClearExpired: Boolean,
    selectedCategory: Category?, onCategorySelected: (Category?) -> Unit,
    selectedSubcategory: FoodSubcategory?, onSubcategorySelected: (FoodSubcategory?) -> Unit,
    onSearchChange: (String) -> Unit, onFilterSelected: (Int) -> Unit,
    onScopeSelected: (Int) -> Unit, onShareConnect: () -> Unit,
    onHome: () -> Unit, onProfile: () -> Unit,
    onVoice: () -> Unit, onAddItem: () -> Unit, onClearExpired: () -> Unit,
    inventoryContent: @Composable () -> Unit, modifier: Modifier = Modifier,
    onReviewRestock: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    Box(modifier.fillMaxSize()) {
        FeatureScrollPage {
            FeatureTopBar("Inventory", onHome, initials, onProfile)
            Spacer(Modifier.height(8.dp))
            FeatureCard(color = PantryColors.Pastel) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Inventory scope", fontSize = 12.sp, color = PantryColors.Muted)
                        Text(
                            "Viewing: ${scopeLabels.getOrElse(selectedScope) { scopeLabels.firstOrNull().orEmpty() }}",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PantryColors.Green
                        )
                    }
                    IconButton(onClick = onShareConnect) {
                        Icon(PantryIcons.Share, "Share & Connect", tint = PantryColors.Green)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scopeLabels.forEachIndexed { index, label ->
                        Surface(
                            onClick = { onScopeSelected(index) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { selected = index == selectedScope },
                            color = if (index == selectedScope) PantryColors.Green else Color.White
                        ) {
                            Text(
                                label,
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (index == selectedScope) Color.White else PantryColors.Green
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onReviewRestock) { Text("Review low-stock items") }
            OutlinedTextField(search, onSearchChange, Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text(searchHint, color = PantryColors.Muted, fontSize = 13.sp) },
                leadingIcon = { Icon(PantryIcons.Search, null, Modifier.size(21.dp), tint = PantryColors.Muted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White, unfocusedBorderColor = PantryColors.Border))
            Spacer(Modifier.height(12.dp))
            VoiceCommandCard("Add or deduct groceries by voice", onVoice)
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filterLabels.forEachIndexed { index, label ->
                    Surface(onClick = { onFilterSelected(index) }, shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.semantics { selected = index == selectedFilter },
                        color = if (index == selectedFilter) PantryColors.Green else PantryColors.Pastel) {
                        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (index == selectedFilter) Color.White else PantryColors.Green)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Category", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PantryColors.Ink)
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InventoryCategoryFilterChip("All categories", selectedCategory == null,
                        PantryColors.Pastel, PantryColors.Green) { onCategorySelected(null) }
                    Category.entries.forEach { category ->
                        InventoryCategoryFilterChip(category.label, selectedCategory == category,
                            Color(category.background), Color(category.foreground)) { onCategorySelected(category) }
                    }
                }
                if (selectedCategory == Category.FOOD_BEVERAGE) {
                    InventoryFoodSubcategoryFilter(selectedSubcategory, onSubcategorySelected)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your inventory", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("$itemCount ${if (itemCount == 1) "item" else "items"}", color = PantryColors.Muted, fontSize = 12.sp)
            }
            if (showClearExpired) {
                TextButton(onClick = onClearExpired, modifier = Modifier.align(Alignment.End)) {
                    Text("Clear expired", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            if (itemCount > 0) {
                inventoryContent()
                Text("Tap an item for its batches · Hold to delete", Modifier.padding(top = 10.dp),
                    color = PantryColors.Muted, fontSize = 11.sp)
            } else {
                Spacer(Modifier.height(8.dp))
                FeatureCard {
                    Icon(PantryIcons.Fridge, null, Modifier.align(Alignment.CenterHorizontally).padding(12.dp).size(36.dp), tint = PantryColors.Green)
                    Text(if (search.isNotBlank() || selectedCategory != null) "No matching items" else when (selectedFilter) {
                        1 -> "No items expiring soon"
                        2 -> "No expired items"
                        else -> "Your inventory is empty"
                    }, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(when {
                        search.isNotBlank() -> "Try another name or change your filters."
                        selectedSubcategory != null -> "Try another food subcategory or stock filter to see your items."
                        selectedCategory != null -> "Try another category or stock filter to see your items."
                        else -> "Add items or choose another filter to see your items."
                    },
                        Modifier.padding(top = 8.dp), fontSize = 13.sp, color = PantryColors.Muted)
                }
            }
        }
        FloatingActionButton(
            onClick = onAddItem,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 16.dp).size(56.dp),
            shape = CircleShape,
            containerColor = PantryColors.Orange,
            contentColor = Color.White,
        ) {
            Icon(PantryIcons.Plus, "Add grocery item", Modifier.size(24.dp))
        }
    }
}

@Composable
private fun InventoryFoodSubcategoryFilter(selectedSubcategory: FoodSubcategory?,
    onSelect: (FoodSubcategory?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val category = Category.FOOD_BEVERAGE
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("Food subcategory", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PantryColors.Muted)
        Box(Modifier.padding(top = 6.dp)) {
            Surface(onClick = { expanded = true }, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.semantics { contentDescription = "Choose food subcategory" },
                color = Color(category.background), contentColor = Color(category.foreground),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(category.foreground).copy(alpha = 0.18f))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedSubcategory?.label ?: "All food subcategories", Modifier.weight(1f), fontSize = 13.sp)
                    Icon(PantryIcons.ChevronDown, null, Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded, { expanded = false }) {
                (listOf<FoodSubcategory?>(null) + FoodSubcategory.entries).forEach { subcategory ->
                    val label = subcategory?.label ?: "All food subcategories"
                    DropdownMenuItem(text = { Text(label) },
                        modifier = Modifier.semantics {
                            contentDescription = "Filter food subcategory: $label"
                            selected = subcategory == selectedSubcategory
                        },
                        onClick = { onSelect(subcategory); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun InventoryCategoryFilterChip(label: String, isSelected: Boolean, background: Color,
    foreground: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.semantics {
            selected = isSelected
            contentDescription = "Filter category: $label"
            stateDescription = if (isSelected) "Selected" else "Not selected"
            role = Role.RadioButton
        },
        border = androidx.compose.foundation.BorderStroke(1.dp, foreground.copy(alpha = if (isSelected) 1f else 0.18f)),
        color = if (isSelected) foreground else background,
        contentColor = if (isSelected) Color.White else foreground) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (isSelected) Icon(PantryIcons.Check, null, Modifier.size(13.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

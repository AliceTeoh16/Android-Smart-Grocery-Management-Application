package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presentation only; values, dropdown options and actions belong to the existing dialog. */
@Composable
fun AddShoppingItemSheet(
    name: String, quantity: String, price: String, category: String, assignee: String,
    categories: List<String>, assignees: List<String>, nameError: String?,
    onNameChange: (String) -> Unit, onQuantityChange: (String) -> Unit,
    onPriceChange: (String) -> Unit, onCategorySelected: (String) -> Unit,
    onAssigneeSelected: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    Surface(color = PantryColors.Cream, contentColor = PantryColors.Ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 100.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(42.dp, 4.dp)
                    .background(PantryColors.Border, RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(PantryColors.Pastel, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(PantryIcons.Basket, null, Modifier.size(23.dp), tint = PantryColors.Green)
                    }
                    Text("Add shopping item", Modifier.weight(1f).padding(start = 12.dp),
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PantryColors.Ink)
                    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(PantryIcons.Close, "Cancel", Modifier.size(22.dp), tint = PantryColors.Muted)
                    }
                }
                Text("A little planning for your pantry.", color = PantryColors.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                ShoppingField("Item name", name, onNameChange, placeholder = "e.g. Noodles", error = nameError,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                Spacer(Modifier.height(16.dp))
                InventoryCategorySelector(category, onCategorySelected)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShoppingField("Quantity", quantity, onQuantityChange, Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    ShoppingField("Total price (RM)", price, onPriceChange, Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text("Estimated total for the quantity above.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = PantryColors.Muted)
                Spacer(Modifier.height(16.dp))
                ShoppingDropdown("Assign to", assignee, assignees, onAssigneeSelected)
                Spacer(Modifier.height(20.dp))
                Surface(color = PantryColors.Pastel, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PantryIcons.Basket, null, Modifier.size(21.dp), tint = PantryColors.Green)
                        Text("Planned items count toward spending once purchased.", Modifier.padding(start = 12.dp),
                            fontSize = 12.sp, color = PantryColors.Green)
                    }
                }
                Spacer(Modifier.height(24.dp))
                FeatureAction("Add item", onSave, icon = PantryIcons.Check)
                Spacer(Modifier.height(10.dp))
                FeatureAction("Cancel", onCancel, color = PantryColors.Pastel, contentColor = PantryColors.Green)
            }
        }
    }
}

@Composable
private fun ShoppingLabel(label: String) {
    Text(label, Modifier.padding(bottom = 8.dp), color = PantryColors.Ink,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ShoppingField(label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, placeholder: String = "", error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    Column(modifier) {
        ShoppingLabel(label)
        OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(14.dp), isError = error != null,
            textStyle = MaterialTheme.typography.bodyMedium, keyboardOptions = keyboardOptions,
            placeholder = { Text(placeholder, color = PantryColors.Muted, fontSize = 14.sp) },
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White, errorContainerColor = Color.White,
                unfocusedBorderColor = PantryColors.Border),
            supportingText = if (error == null) null else ({ Text(error) }))
    }
}

@Composable
private fun ShoppingDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        ShoppingLabel(label)
        Box {
            Surface(onClick = { expanded = true }, color = Color.White, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PantryColors.Border)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), fontSize = 15.sp)
                    Icon(PantryIcons.ChevronDown, "Choose $label", Modifier.size(22.dp), tint = PantryColors.Muted)
                }
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = PantryColors.Cream) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

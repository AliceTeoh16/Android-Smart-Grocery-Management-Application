package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presentation only. Every editable value and action is supplied by the existing form host. */
@Composable
fun AddGroceryItemScreen(
    name: String, quantity: String, price: String, category: String, expiryDate: String,
    threshold: String, categories: List<String>, onNameChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit, onPriceChange: (String) -> Unit,
    onCategorySelected: (String) -> Unit, onThresholdChange: (String) -> Unit,
    onExpiryDateClick: () -> Unit, onSaveItem: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier, initials: String? = null, nameError: String? = null,
    isEditMode: Boolean = false,
    onScanIngredients: () -> Unit,
    destinationName: String = "Personal (private)", destinationHousehold: Boolean = false,
    destinationReady: Boolean = true, saving: Boolean = false, saveError: String? = null,
    householdOptions: List<HouseholdContextOption> = emptyList(),
    onDestinationSelected: (Boolean) -> Unit = {}, onHouseholdSelected: (String) -> Unit = {},
) {
    if (isEditMode) {
        EditGroceryItemSheet(
            name, quantity, price, category, expiryDate, threshold, categories,
            onNameChange, onQuantityChange, onPriceChange, onCategorySelected,
            onThresholdChange, onExpiryDateClick, onSaveItem, onBack, modifier, nameError, destinationName, destinationHousehold, destinationReady, saving, saveError,
        )
        return
    }
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
        Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 100.dp)) {
            FeatureHeader(if (isEditMode) "Edit inventory item" else "Add inventory item", onBack, initials)
            Spacer(Modifier.height(8.dp))
            InventoryDestinationCard(destinationName, destinationHousehold, householdOptions,
                onDestinationSelected, onHouseholdSelected, enabled = destinationReady && !saving)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(14.dp))
            FeatureCard(modifier = Modifier.clickable(enabled = destinationReady && !saving, onClickLabel = "Scan ingredients", onClick = onScanIngredients), color = PantryColors.Pastel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PantryIcons.Barcode, null, Modifier.size(28.dp), tint = PantryColors.Green)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Skip the typing", color = PantryColors.Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Scan food, household essentials or personal care.", color = PantryColors.Muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(PantryIcons.ArrowRight, null, Modifier.size(22.dp), tint = PantryColors.Green)
                }
            }
            Spacer(Modifier.height(14.dp))
            FormField("Item name", name, onNameChange, placeholder = "e.g. Greek yogurt", error = nameError)
            Spacer(Modifier.height(12.dp))
            InventoryCategorySelector(category, onCategorySelected)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField("Quantity", quantity, onQuantityChange, Modifier.weight(1f), KeyboardType.Number)
                FormField("Unit", "pcs", {}, Modifier.weight(1f), enabled = false)
            }
            Spacer(Modifier.height(12.dp))
            FormField("Total price (RM)", price, onPriceChange, keyboardType = KeyboardType.Decimal)
            Text("Only saved purchases count toward budget.", Modifier.padding(top = 8.dp),
                fontSize = 12.sp, color = PantryColors.Muted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Expiry date (optional)")
                    Surface(onClick = onExpiryDateClick, shape = RoundedCornerShape(14.dp), color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, PantryColors.Border)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(expiryDate, Modifier.weight(1f), fontSize = 14.sp)
                            Icon(PantryIcons.Calendar, "Choose expiry date", Modifier.size(20.dp), tint = PantryColors.Muted)
                        }
                    }
                }
                FormField("Storage", "Not set", {}, Modifier.weight(1f), enabled = false)
            }
            Text("Unit and storage options are not available yet.", Modifier.padding(top = 8.dp),
                fontSize = 12.sp, color = PantryColors.Muted)
            var advanced by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(0.dp)) {
                Text(if (advanced) "Hide low-stock setting" else "Low-stock setting", fontSize = 12.sp)
            }
            if (advanced) FormField("Low-stock warning threshold", threshold, onThresholdChange, keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(48.dp))
            Button(onClick = onSaveItem, enabled = destinationReady && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange)) {
                Text(if (saving) "Saving…" else if (destinationHousehold) "Save to household" else "Save privately")
            }
            Text("Expiry reminders follow your household warning setting.", Modifier.padding(top = 12.dp),
                fontSize = 12.sp, color = PantryColors.Muted)
        }
    }
}

/** Uses the same form values and callbacks as the full-screen grocery form. */
@Composable
private fun EditGroceryItemSheet(
    name: String, quantity: String, price: String, category: String, expiryDate: String,
    threshold: String, categories: List<String>, onNameChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit, onPriceChange: (String) -> Unit,
    onCategorySelected: (String) -> Unit, onThresholdChange: (String) -> Unit,
    onExpiryDateClick: () -> Unit, onSaveItem: () -> Unit, onBack: () -> Unit,
    modifier: Modifier, nameError: String?, destinationName: String, destinationHousehold: Boolean,
    destinationReady: Boolean, saving: Boolean, saveError: String?,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    Surface(color = PantryColors.Cream, contentColor = PantryColors.Ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
            Column(modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 100.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(42.dp, 4.dp)
                    .background(PantryColors.Border, RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(PantryColors.Pastel, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(PantryIcons.Fridge, null, Modifier.size(23.dp), tint = PantryColors.Green)
                    }
                    Text("Edit inventory item", Modifier.weight(1f).padding(start = 12.dp),
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PantryColors.Ink)
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(PantryIcons.Close, "Cancel", Modifier.size(22.dp), tint = PantryColors.Muted)
                    }
                }
                Text("Keep your pantry details up to date.", fontSize = 13.sp, color = PantryColors.Muted)
                Spacer(Modifier.height(22.dp))
                InventoryDestinationCard(destinationName, destinationHousehold, emptyList(), {}, {}, readOnly = true)
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(16.dp))
                FormField("Item name", name, onNameChange, error = nameError)
                Spacer(Modifier.height(16.dp))
                InventoryCategorySelector(category, onCategorySelected)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField("Quantity", quantity, onQuantityChange, Modifier.weight(1f), KeyboardType.Number)
                    FormField("Total price (RM)", price, onPriceChange, Modifier.weight(1f), KeyboardType.Decimal)
                }
                Spacer(Modifier.height(16.dp))
                FieldLabel("Expiry date (optional)")
                Surface(onClick = onExpiryDateClick, shape = RoundedCornerShape(14.dp), color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PantryColors.Border)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(expiryDate, Modifier.weight(1f), fontSize = 15.sp)
                        Icon(PantryIcons.Calendar, "Choose expiry date", Modifier.size(22.dp), tint = PantryColors.Green)
                    }
                }
                Spacer(Modifier.height(16.dp))
                FormField("Low-stock warning threshold", threshold, onThresholdChange, keyboardType = KeyboardType.Number)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onSaveItem, enabled = destinationReady && !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (saving) "Saving…" else "Save changes")
                }
                Spacer(Modifier.height(10.dp))
                FeatureAction("Cancel", onBack, color = PantryColors.Pastel, contentColor = PantryColors.Green)
            }
        }
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(label, Modifier.padding(bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PantryColors.Ink)
}

@Composable
private fun FormField(label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "", error: String? = null, enabled: Boolean = true) {
    Column(modifier) {
        FieldLabel(label)
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), singleLine = true,
            enabled = enabled, isError = error != null, shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            placeholder = { Text(placeholder, fontSize = 14.sp, color = PantryColors.Muted) },
            trailingIcon = if (label == "Unit" || label == "Storage") ({
                Icon(PantryIcons.ChevronDown, null, tint = PantryColors.Muted, modifier = Modifier.size(20.dp))
            }) else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                disabledContainerColor = Color.White, disabledBorderColor = PantryColors.Border,
                disabledTextColor = PantryColors.Muted, unfocusedBorderColor = PantryColors.Border),
            supportingText = if (error == null) null else ({ Text(error) }))
    }
}

@Composable
private fun FormDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        FieldLabel(label)
        Box {
            Surface(onClick = { expanded = true }, shape = RoundedCornerShape(14.dp), color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, PantryColors.Border)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), fontSize = 15.sp)
                    Icon(PantryIcons.ChevronDown, "Choose $label", Modifier.size(22.dp), tint = PantryColors.Muted)
                }
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

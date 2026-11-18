package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun InventoryDestinationCard(name: String, household: Boolean, options: List<HouseholdContextOption>,
    onScope: (Boolean) -> Unit, onHousehold: (String) -> Unit, enabled: Boolean = true, readOnly: Boolean = false) {
    FeatureCard(color = PantryColors.Pastel) {
        Text("Save inventory to", fontWeight = FontWeight.SemiBold, color = PantryColors.Green)
        if (!readOnly) {
            Spacer(Modifier.height(8.dp))
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                secondaryContainer = PantryColors.Green, onSecondaryContainer = Color.White)) {
                ScopeFilterBar(if (household) 1 else 0, { if (enabled) onScope(it == 1) })
            }
        }
        Text(name, Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
        Text(if (household) "Everyone in this household can see and update these items."
            else "Only your account can see these items.", color = PantryColors.Muted)
        if (!readOnly && household && options.size > 1) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }, enabled = enabled) { Text("Choose household") }
                DropdownMenu(expanded, { expanded = false }) {
                    options.forEach { option -> DropdownMenuItem(text = { Text(option.name) },
                        onClick = { expanded = false; onHousehold(option.id) }) }
                }
            }
        }
    }
}

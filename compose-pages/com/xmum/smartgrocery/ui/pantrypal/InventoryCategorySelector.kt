package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmum.smartgrocery.utils.Category
import com.xmum.smartgrocery.utils.GroceryCategories

/** Non-food selections never expose a subcategory control. */
@Composable
internal fun InventoryCategorySelector(value: String, onSelect: (String) -> Unit, enabled: Boolean = true,
    itemName: String? = null) {
    val category = GroceryCategories.mainCategory(value)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryDropdown("Category", category.label, GroceryCategories.main, enabled, category, itemName, onSelect)
        if (category == Category.FOOD_BEVERAGE) {
            CategoryDropdown("Subcategory", GroceryCategories.subcategory(value) ?: "Choose subcategory",
                GroceryCategories.foodSubcategories, enabled, category, itemName, onSelect)
        }
    }
}

@Composable
private fun CategoryDropdown(label: String, value: String, options: List<String>, enabled: Boolean,
    category: Category, itemName: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, Modifier.padding(bottom = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box {
            Surface(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(14.dp),
                color = Color(category.background), contentColor = Color(category.foreground)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Icon(PantryIcons.ChevronDown, "Choose $label" + (itemName?.let { " for $it" } ?: ""), Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded && enabled, { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) },
                        modifier = Modifier.semantics { contentDescription = "Select $label: $option" },
                        onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

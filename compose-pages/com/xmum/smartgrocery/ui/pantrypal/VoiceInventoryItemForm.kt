package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmum.smartgrocery.utils.VoiceInventoryItemDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Editable presentation for the unsaved item; the confirmation owner performs the save. */
@Composable
fun VoiceInventoryItemForm(draft: VoiceInventoryItemDraft, onChange: (VoiceInventoryItemDraft) -> Unit,
    onExpiryDateClick: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("New inventory item", color = PantryColors.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Check the details before saving.", color = PantryColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            DraftField("Item name", draft.name, { onChange(draft.copy(name = it)) })
            Spacer(Modifier.height(12.dp))
            InventoryCategorySelector(draft.category, { category ->
                onChange(draft.copy(category = category,
                    expiryDate = if (com.xmum.smartgrocery.utils.GroceryCategories.isFood(category)) draft.expiryDate else null,
                    threshold = com.xmum.smartgrocery.utils.GroceryCategories.defaultThreshold(category).toString()))
            })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DraftField("Quantity", draft.quantity, { onChange(draft.copy(quantity = it)) },
                    Modifier.weight(1f), KeyboardType.Number)
                DraftField("Total price (RM)", draft.price, { onChange(draft.copy(price = it)) },
                    Modifier.weight(1f), KeyboardType.Decimal)
            }
            Spacer(Modifier.height(12.dp))
            Text("Expiry date (optional)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Surface(onClick = onExpiryDateClick, shape = RoundedCornerShape(14.dp), color = PantryColors.Cream,
                border = BorderStroke(1.dp, PantryColors.Border), modifier = Modifier.padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(com.xmum.smartgrocery.utils.DateUtils.formatDate(draft.expiryDate),
                        Modifier.weight(1f), fontSize = 14.sp)
                    Icon(PantryIcons.Calendar, "Choose expiry date", Modifier.size(20.dp), tint = PantryColors.Green)
                }
            }
            Spacer(Modifier.height(12.dp))
            DraftField("Low-stock warning threshold", draft.threshold, { onChange(draft.copy(threshold = it)) },
                keyboardType = KeyboardType.Number)
            draft.validationError?.let {
                Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DraftField(label: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text) {
    Column(modifier) {
        Text(label, Modifier.padding(bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = true, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = PantryColors.Cream,
                unfocusedContainerColor = PantryColors.Cream, unfocusedBorderColor = PantryColors.Border))
    }
}

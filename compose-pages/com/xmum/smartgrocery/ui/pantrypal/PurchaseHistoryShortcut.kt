  package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PurchaseHistoryShortcut(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClick) {
            Icon(PantryIcons.History, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Purchase history", style = MaterialTheme.typography.labelMedium)
        }
    }
}

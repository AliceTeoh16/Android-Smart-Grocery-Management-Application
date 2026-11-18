package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ScopeFilterBar(selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Personal", "Household").forEachIndexed { index, label ->
            FilterChip(selected = selectedIndex == index, onClick = { onSelected(index) },
                label = { Text(label) }, modifier = Modifier.weight(1f))
        }
    }
}

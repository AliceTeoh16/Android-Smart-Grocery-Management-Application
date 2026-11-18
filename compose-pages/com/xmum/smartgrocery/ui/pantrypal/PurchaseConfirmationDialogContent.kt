package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Only renders the existing purchase form; validation and purchase calculations stay in its host. */
@Composable
fun PurchaseConfirmationDialogContent(
    itemName: String, price: String, priceError: String?,
    onPriceChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    Surface(color = PantryColors.Cream, contentColor = PantryColors.Ink, shape = RoundedCornerShape(28.dp)) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(lineHeight = TextUnit.Unspecified)) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 100.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Confirm purchase", Modifier.weight(1f), color = PantryColors.Ink,
                        fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                        Icon(PantryIcons.Close, "Cancel", Modifier.size(22.dp), tint = PantryColors.Muted)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Surface(color = PantryColors.Pastel, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PantryIcons.Basket, null, Modifier.size(28.dp), tint = PantryColors.Green)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(itemName, color = PantryColors.Green, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Ready for your pantry", Modifier.padding(top = 4.dp),
                                color = PantryColors.Green, fontSize = 12.sp)
                        }
                    }
                }
                Text("Enter the total price you paid for $itemName.", Modifier.padding(top = 18.dp),
                    fontSize = 13.sp, color = PantryColors.Muted)
                Spacer(Modifier.height(20.dp))
                Text("Total price paid (RM)", Modifier.padding(bottom = 8.dp), color = PantryColors.Ink,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = price, onValueChange = onPriceChange, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(14.dp), isError = priceError != null,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    placeholder = { Text("0.00", color = PantryColors.Muted) },
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White, errorContainerColor = Color.White,
                        unfocusedBorderColor = PantryColors.Border),
                    supportingText = if (priceError == null) null else ({ Text(priceError) }))
                Spacer(Modifier.height(24.dp))
                FeatureAction("Confirm purchase", onConfirm, icon = PantryIcons.Check)
                Spacer(Modifier.height(10.dp))
                FeatureAction("Cancel", onCancel, color = PantryColors.Pastel, contentColor = PantryColors.Green)
            }
        }
    }
}

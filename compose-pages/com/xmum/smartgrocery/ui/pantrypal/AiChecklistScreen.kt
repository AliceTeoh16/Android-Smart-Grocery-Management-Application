package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmum.smartgrocery.ui.recipes.ChecklistUiState
import com.xmum.smartgrocery.ui.recipes.ChecklistErrorMessage
import com.xmum.smartgrocery.data.repository.RecipeRepository

@Composable
fun AiChecklistScreen(
    state: ChecklistUiState, prompt: String, householdName: String,
    onPromptChange: (String) -> Unit, onFocusChanged: (Boolean) -> Unit,
    onGenerate: () -> Unit, onSubmit: () -> Unit, onHistory: () -> Unit, onHome: () -> Unit,
    showAdd: Boolean, canAdd: Boolean, addText: String, onAddSelected: () -> Unit,
    showMarkCooked: Boolean, canMarkCooked: Boolean, onMarkAsCooked: () -> Unit,
    onVoice: () -> Unit, showSearchHistory: Boolean, historyContent: @Composable () -> Unit,
    ingredientContent: @Composable () -> Unit, pantryContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onInventorySelectionChanged: (RecipeRepository.DeductedInventoryItem, Boolean) -> Unit = { _, _ -> },
) {
    FeatureScrollPage(modifier) {
        FeatureTopBar("AI Checklist", onHome, "", {}, trailing = {
            Surface(onClick = onHistory, shape = CircleShape, color = PantryColors.Pastel) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(PantryIcons.History, "Checklist History", Modifier.size(21.dp), tint = PantryColors.Green)
                }
            }
        })
        Text("What would you like to cook?", Modifier.padding(top = 8.dp, bottom = 10.dp),
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        OutlinedTextField(prompt, onPromptChange,
            Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
            placeholder = { Text("Beef noodle for 2 people", fontSize = 14.sp, color = PantryColors.Muted) },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White, unfocusedBorderColor = PantryColors.Border),
            trailingIcon = {
                FilledIconButton(onClick = onGenerate, enabled = !state.isLoading,
                    shape = RoundedCornerShape(11.dp), modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = PantryColors.Orange)) {
                    Icon(PantryIcons.ArrowUp, "Generate checklist", Modifier.size(20.dp))
                }
            })
        if (showSearchHistory) {
            Text("Recent searches", Modifier.padding(top = 12.dp), fontSize = 12.sp, color = PantryColors.Muted)
            historyContent()
        }
        if (state.isLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = PantryColors.Green)
            Text("Checking your pantry…", Modifier.padding(top = 8.dp), color = PantryColors.Muted, fontSize = 12.sp)
        }
        val result = state.checklistResult
        if (result != null) {
            Spacer(Modifier.height(12.dp))
            FeatureCard(color = PantryColors.Pastel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = PantryColors.Pale) {
                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                            Icon(PantryIcons.Chef, null, Modifier.size(28.dp), tint = PantryColors.Green)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(result.dishName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("${result.servings} servings · AI suggested", fontSize = 12.sp, color = PantryColors.Green)
                    }
                }
                Text(householdName, Modifier.padding(top = 12.dp), fontSize = 12.sp, color = PantryColors.Green)
            }
            if (result.steps.isNotEmpty()) {
                var showSteps by rememberSaveable(result.dishName, result.servings, result.steps) { mutableStateOf(false) }
                Spacer(Modifier.height(12.dp))
                FeatureCard {
                    TextButton(onClick = { showSteps = !showSteps }, modifier = Modifier.fillMaxWidth()) {
                        Icon(PantryIcons.Chef, null, Modifier.size(20.dp))
                        Text(if (showSteps) "Hide Recipe Steps" else "View Recipe Steps",
                            Modifier.weight(1f).padding(horizontal = 10.dp), fontWeight = FontWeight.SemiBold)
                        Text("${result.steps.size} steps", color = PantryColors.Muted, fontSize = 12.sp)
                    }
                    if (showSteps) {
                        HorizontalDivider(color = PantryColors.Border)
                        Text("For ${result.dishName} · ${result.servings} servings",
                            Modifier.padding(top = 12.dp, bottom = 4.dp), fontSize = 12.sp, color = PantryColors.Muted)
                        result.steps.forEachIndexed { index, instruction ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
                                Surface(shape = CircleShape, color = PantryColors.Pastel) {
                                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                        Text("${index + 1}", color = PantryColors.Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                                Text(instruction, Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp, lineHeight = 22.sp)
                            }
                        }
                    }
                }
            } else {
                Text("Generate a new checklist to include matching recipe steps.",
                    Modifier.padding(top = 12.dp), fontSize = 12.sp, color = PantryColors.Muted)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ingredient checklist", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                FeatureBadge("${result.missingChecklist.size} missing", PantryColors.Peach, PantryColors.Orange)
            }
            FeatureCard {
                state.selectableInventoryItems.forEach { selectable ->
                    val ingredient = selectable.inventoryItem
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selectable.isSelected,
                            onCheckedChange = { onInventorySelectionChanged(ingredient, it) },
                            enabled = state.activeChecklistStatus == "pending" && !state.isLoading && !state.isAddingToShopping,
                            modifier = Modifier.semantics { contentDescription = "Use ${ingredient.item}" },
                            colors = CheckboxDefaults.colors(checkedColor = PantryColors.Green))
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(ingredient.item, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(ingredient.usedQuantity.replace(Regex("""\s*\(Fully Covered\)""", RegexOption.IGNORE_CASE), "").trim(),
                                fontSize = 12.sp, color = PantryColors.Muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("(Fully Covered)", color = PantryColors.Green, fontSize = 10.sp)
                            Text("In stock", color = PantryColors.Green, fontSize = 10.sp)
                            if (selectable.isAddedToShopping) {
                                Spacer(Modifier.height(4.dp))
                                FeatureBadge("Added", PantryColors.Pastel, PantryColors.Green)
                            }
                        }
                    }
                    HorizontalDivider(color = PantryColors.Border)
                }
                ingredientContent()
                if (result.missingChecklist.isEmpty()) {
                    Text("No missing items", Modifier.padding(vertical = 12.dp), color = PantryColors.Green, fontSize = 13.sp)
                }
            }
            if (state.selectableInventoryItems.isNotEmpty()) Text(
                "Checked in-stock items will be deducted when you mark this meal as cooked.",
                Modifier.padding(top = 8.dp), fontSize = 11.sp, color = PantryColors.Muted)
        }
        if (state.generationError != null) {
            val error = ChecklistErrorMessage.from(state.generationError)
            Spacer(Modifier.height(12.dp))
            FeatureCard(color = PantryColors.Peach) {
                Text(error.title, fontWeight = FontWeight.Bold)
                Text(error.message, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = PantryColors.Ink)
                if (error.showRetry) TextButton(onClick = onGenerate, enabled = !state.isLoading && prompt.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)) { Text("Try again", color = PantryColors.Green) }
            }
        } else if (result == null && !state.isLoading) {
            Spacer(Modifier.height(16.dp))
            FeatureCard(color = PantryColors.Pastel) {
                Icon(PantryIcons.Chef, null, Modifier.size(36.dp), tint = PantryColors.Green)
                Text("Ready to check", Modifier.padding(top = 12.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Type a dish request like: Tomato Beef Noodles for 4 people.",
                    Modifier.padding(top = 8.dp), fontSize = 13.sp, color = PantryColors.Green)
            }
        }
        if (showAdd) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAddSelected, enabled = canAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange)) {
                Icon(PantryIcons.Plus, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(addText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (showMarkCooked) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onMarkAsCooked, enabled = canMarkCooked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Pastel, contentColor = PantryColors.Green)) {
                Icon(PantryIcons.Chef, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Mark as cooked", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("Marking as cooked updates the ingredients in your pantry.", Modifier.padding(top = 12.dp),
                fontSize = 11.sp, color = PantryColors.Muted)
        }
        var showPantry by rememberSaveable { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showPantry = !showPantry }) { Text("Your pantry ingredients", fontSize = 12.sp) }
            TextButton(onClick = onVoice) { Text("Voice input", fontSize = 12.sp) }
        }
        if (showPantry) pantryContent()
    }
}

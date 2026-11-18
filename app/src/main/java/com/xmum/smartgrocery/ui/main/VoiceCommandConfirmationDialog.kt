package com.xmum.smartgrocery.ui.main

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.ui.pantrypal.PantryColors
import com.xmum.smartgrocery.ui.pantrypal.PantryPalTheme
import com.xmum.smartgrocery.ui.pantrypal.VoiceInventoryItemForm
import com.xmum.smartgrocery.utils.VoiceAction
import com.xmum.smartgrocery.utils.VoiceCommandReview
import com.xmum.smartgrocery.utils.VoiceInventoryItemDraft
import com.xmum.smartgrocery.utils.ShoppingVoiceMerge
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.util.Calendar

/** Restorable review UI. Only its explicit Confirm button dispatches a command. */
class VoiceCommandConfirmationDialog : DialogFragment() {
    private var submitted = false
    internal var newItemDrafts: List<VoiceInventoryItemDraft>? by mutableStateOf(null)
        private set
    private var loadError: String? by mutableStateOf(null)
    internal var shoppingAssignee: String by mutableStateOf("Household")
        private set
    private var shoppingItems: List<ShoppingListItem>? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        submitted = savedInstanceState?.getBoolean("submitted") ?: false
        shoppingAssignee = (savedInstanceState?.getString("shopping_assignee")
            ?: requireArguments().getString("shopping_assignee"))
            .takeIf { it in ShoppingVoiceMerge.assignees } ?: "Household"
        val shopping = requireArguments().getBoolean("shopping")
        val review = VoiceCommandReview.prepare(listOf(requireArguments().getString("transcript").orEmpty()), shopping)
        val restored = savedInstanceState?.getParcelableArrayList<Bundle>("new_item_drafts")
        if (shopping && review.canConfirm) lifecycleScope.launch {
            try {
                (requireActivity().application as SmartGroceryApp).shoppingListRepository.shoppingListItems.collect {
                    shoppingItems = it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loadError = "Couldn’t load shopping details. Cancel and try again."
            }
        }
        when {
            shopping || !review.canConfirm -> newItemDrafts = emptyList()
            restored != null -> newItemDrafts = restored.map { fields ->
                VoiceInventoryItemDraft(fields.getInt("index"), fields.getString("name").orEmpty(),
                    fields.getString("quantity").orEmpty(), fields.getString("price").orEmpty(),
                    com.xmum.smartgrocery.utils.GroceryCategories.selection(fields.getString("category").orEmpty()), fields.getLong("expiry").takeIf { it > 0L }, fields.getString("threshold").orEmpty())
            }
            else -> lifecycleScope.launch {
                try {
                    val repository = (requireActivity().application as SmartGroceryApp).groceryRepository
                    val drafts = mutableListOf<VoiceInventoryItemDraft>()
                    review.commands.forEachIndexed { index, command ->
                        if (command.action == VoiceAction.ADD) {
                            val existing = repository.getItemByName(command.itemName)
                                ?: repository.findBestMatchingItem(command.itemName)
                            if (existing == null) drafts += VoiceInventoryItemDraft.fromCommand(index, command)
                        }
                    }
                    newItemDrafts = drafts
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    loadError = "Couldn’t load inventory details. Cancel and try again."
                }
            }
        }
    }

    internal fun updateNewItemDraft(draft: VoiceInventoryItemDraft) {
        newItemDrafts = newItemDrafts?.map { if (it.commandIndex == draft.commandIndex) draft else it }
    }

    private fun chooseExpiry(draft: VoiceInventoryItemDraft) {
        val calendar = Calendar.getInstance().apply { timeInMillis = draft.expiryDate ?: System.currentTimeMillis() }
        android.app.DatePickerDialog(requireContext(), { _, year, month, day ->
            val chosen = Calendar.getInstance().apply {
                timeInMillis = draft.expiryDate ?: System.currentTimeMillis()
                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
            }
            // Use the latest other fields if the draft changed while the date picker was open.
            newItemDrafts?.firstOrNull { it.commandIndex == draft.commandIndex }?.let {
                updateNewItemDraft(it.copy(expiryDate = chosen.timeInMillis))
            }
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
            setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Not Set") { _, _ ->
                newItemDrafts?.firstOrNull { it.commandIndex == draft.commandIndex }?.let { updateNewItemDraft(it.copy(expiryDate = null)) }
            }
        }.show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val transcript = requireArguments().getString("transcript").orEmpty()
        val shopping = requireArguments().getBoolean("shopping")
        val review = VoiceCommandReview.prepare(listOf(transcript), shopping)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PantryPalTheme {
                    Surface(color = PantryColors.Cream, shape = RoundedCornerShape(28.dp)) {
                        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
                        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)) {
                            val drafts = newItemDrafts
                            val shoppingPreview = if (shopping && shoppingItems != null && review.canConfirm) runCatching {
                                ShoppingVoiceMerge.preview(shoppingItems.orEmpty(), review.commands, shoppingAssignee)
                            } else null
                            val canSave = review.canConfirm && drafts != null && drafts.all { it.validationError == null } &&
                                loadError == null && (!shopping || shoppingPreview?.isSuccess == true)
                            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                                .padding(top = 24.dp, bottom = 100.dp)) {
                                Text("Check voice command", fontSize = 21.sp, fontWeight = FontWeight.Bold,
                                    color = PantryColors.Ink)
                                Text(if (shopping) "Shopping List" else "Inventory",
                                    Modifier.padding(top = 8.dp), fontSize = 13.sp, color = PantryColors.Green)
                                if (requireArguments().getBoolean("language_unconfirmed")) {
                                    Text(LANGUAGE_UNCONFIRMED_NOTICE, Modifier.padding(top = 12.dp),
                                        fontSize = 13.sp, color = PantryColors.Muted)
                                }
                                Text("You said", Modifier.padding(top = 20.dp, bottom = 8.dp),
                                    fontSize = 12.sp, color = PantryColors.Muted)
                                Surface(color = PantryColors.Pastel, shape = RoundedCornerShape(16.dp)) {
                                    Text(review.transcript, Modifier.fillMaxWidth().padding(16.dp), color = PantryColors.Green)
                                }
                                if (review.canConfirm) {
                                    if (shopping && review.commands.any { it.action == VoiceAction.ADD }) {
                                        Text("Who is this for?", Modifier.padding(top = 18.dp), fontWeight = FontWeight.SemiBold)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            ShoppingVoiceMerge.assignees.forEach { assignee ->
                                                FilterChip(selected = shoppingAssignee == assignee,
                                                    onClick = { shoppingAssignee = assignee },
                                                    label = { Text(if (assignee == "Me") "Me only" else "Household") },
                                                    modifier = Modifier.weight(1f),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = PantryColors.Green,
                                                        selectedLabelColor = androidx.compose.ui.graphics.Color.White))
                                            }
                                        }
                                        Text("Applies to all items in this command. Matching items in that list will be combined.",
                                            fontSize = 12.sp, color = PantryColors.Muted)
                                    }
                                    Text("Please check these actions", Modifier.padding(top = 20.dp, bottom = 10.dp),
                                        fontWeight = FontWeight.SemiBold)
                                    review.commands.forEachIndexed { index, command ->
                                        val draft = drafts?.firstOrNull { it.commandIndex == index }
                                        val action = when (command.action) {
                                            VoiceAction.ADD -> "Add"
                                            VoiceAction.DEDUCT -> "Deduct"
                                            else -> "Open recipes for"
                                        }
                                        if (draft != null) {
                                            VoiceInventoryItemForm(draft, ::updateNewItemDraft, { chooseExpiry(draft) })
                                        } else Surface(color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.padding(bottom = 8.dp)) {
                                            val planned = if (command.action == VoiceAction.ADD && shopping)
                                                shoppingPreview?.getOrNull()?.getOrNull(review.commands.take(index).count { it.action == VoiceAction.ADD }) else null
                                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                                Text(if (command.action == VoiceAction.RECIPE) "$action ${command.itemName}"
                                                    else "$action ${command.quantity} × ${command.itemName}",
                                                    fontWeight = FontWeight.SemiBold)
                                                planned?.let { plan ->
                                                    Text(if (plan.previous == null) "New item · Total ${plan.total}"
                                                        else "Existing ${plan.previous} + ${plan.added} = ${plan.total}",
                                                        Modifier.padding(top = 6.dp), fontSize = 13.sp, color = PantryColors.Green)
                                                }
                                            }
                                        }
                                    }
                                    if (shopping && shoppingItems == null && loadError == null) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                                        Text("Checking shopping list…", Modifier.padding(top = 8.dp), fontSize = 12.sp)
                                    }
                                    shoppingPreview?.exceptionOrNull()?.let {
                                        Text(it.message ?: "Check the combined quantities.", Modifier.padding(top = 12.dp),
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                    if (drafts == null && loadError == null) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                                        Text("Checking inventory details…", Modifier.padding(top = 8.dp), fontSize = 12.sp)
                                    }
                                    loadError?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
                                    Text(if (shopping) "Nothing is added until you confirm."
                                        else "Nothing is saved until you confirm. New items will be saved directly with the details above.",
                                        Modifier.padding(top = 8.dp), fontSize = 12.sp, color = PantryColors.Muted)
                                } else {
                                    Text(review.error.orEmpty(), Modifier.padding(top = 18.dp),
                                        color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(24.dp))
                                Button(onClick = {
                                    if (!submitted && canSave) {
                                        val newItems = drafts.orEmpty().associate { it.commandIndex to it.toGroceryItem() }
                                        submitted = true
                                        dismiss()
                                        (activity as? MainActivity)?.confirmVoiceCommand(review.transcript, shopping, newItems, shoppingAssignee)
                                    }
                                }, enabled = canSave && !submitted,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange)) {
                                    Text(if (drafts?.isNotEmpty() == true) "Save & confirm" else "Confirm", fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = {
                                    dismiss()
                                    (activity as? MainActivity)?.editVoiceCommand(review.transcript, shopping, shoppingAssignee)
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Pastel, contentColor = PantryColors.Green)) {
                                    Text("Edit command", fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = { dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val density = resources.displayMetrics.density
            setLayout((resources.displayMetrics.widthPixels - (48 * density).toInt()).coerceAtMost((560 * density).toInt()),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("submitted", submitted)
        outState.putString("shopping_assignee", shoppingAssignee)
        newItemDrafts?.let { drafts ->
            outState.putParcelableArrayList("new_item_drafts", ArrayList(drafts.map { draft ->
                Bundle().apply {
                    putInt("index", draft.commandIndex); putString("name", draft.name)
                    putString("quantity", draft.quantity); putString("price", draft.price)
                    putString("category", draft.category); putLong("expiry", draft.expiryDate ?: 0L)
                    putString("threshold", draft.threshold)
                }
            }))
        }
    }

    companion object {
        const val TAG = "VoiceCommandConfirmation"
        internal const val LANGUAGE_UNCONFIRMED_NOTICE = "Your speech service didn’t report a language. Check the recognized words, items and quantities before confirming."
        fun newInstance(transcript: String, shopping: Boolean, languageUnconfirmed: Boolean = false,
            shoppingAssignee: String = "Household") = VoiceCommandConfirmationDialog().apply {
            arguments = Bundle().apply {
                putString("transcript", transcript)
                putBoolean("shopping", shopping)
                putBoolean("language_unconfirmed", languageUnconfirmed)
                putString("shopping_assignee", shoppingAssignee)
            }
        }
    }
}

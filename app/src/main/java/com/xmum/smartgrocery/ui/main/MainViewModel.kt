package com.xmum.smartgrocery.ui.main

import com.xmum.smartgrocery.data.local.dao.DataScope
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.utils.VoiceAction
import com.xmum.smartgrocery.utils.VoiceParser
import com.xmum.smartgrocery.utils.VoiceCommand
import com.xmum.smartgrocery.utils.VoiceCommandReview
import com.xmum.smartgrocery.utils.ShoppingVoiceMerge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class VoiceResult {
    data class Success(val message: String) : VoiceResult()
    data class OpenQuickAdd(val itemName: String, val quantity: Int) : VoiceResult()
    data class OpenRecipes(val prompt: String) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartGroceryApp
    private val groceryRepository = app.groceryRepository
    private val shoppingListRepository = app.shoppingListRepository
    private val _voiceResultEvent = MutableSharedFlow<VoiceResult>()
    val voiceResultEvent = _voiceResultEvent.asSharedFlow()

    fun processVoiceCommand(commandText: String) {
        processVoiceCommandOptions(listOf(commandText))
    }

    fun processVoiceCommandOptions(commandTexts: List<String>) {
        viewModelScope.launch {
            val parsedCommands = commandTexts
                .asSequence()
                .map { VoiceParser.parseAll(it) }
                .firstOrNull { commands -> commands.any { it.action != VoiceAction.UNKNOWN } }
                ?: VoiceParser.parseAll(commandTexts.firstOrNull())

            applyInventoryVoiceCommands(parsedCommands)
        }
    }

    /** Called only after the complete review, including any new-item fields, is confirmed. */
    fun processConfirmedInventoryVoiceCommand(transcript: String, newItems: Map<Int, GroceryItem>, target: DataScope? = null) {
        val review = VoiceCommandReview.prepare(listOf(transcript), shopping = false)
        if (!review.canConfirm) return
        viewModelScope.launch {
            try {
                if (target == null) applyInventoryVoiceCommands(review.commands, newItems)
                else app.sessionManager.withInventoryDestination(target) { applyInventoryVoiceCommands(review.commands, newItems) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _voiceResultEvent.emit(VoiceResult.Error(error.message ?: "Could not save inventory.")) }
        }
    }

    private suspend fun applyInventoryVoiceCommands(parsedCommands: List<VoiceCommand>,
        newItems: Map<Int, GroceryItem>? = null) {
        parsedCommands.forEachIndexed { index, command ->
            when (command.action) {
                VoiceAction.ADD -> {
                    val reviewedItem = newItems?.get(index)
                    if (reviewedItem != null) {
                        groceryRepository.insert(reviewedItem)
                        _voiceResultEvent.emit(VoiceResult.Success("${reviewedItem.name} added to inventory"))
                        return@forEachIndexed
                    }
                    val existing = findInventoryItem(command.itemName)
                    if (existing != null) {
                        val newQty = existing.quantity + command.quantity
                        groceryRepository.updateQuantityFromVoiceAdd(existing.id, newQty)
                        _voiceResultEvent.emit(
                            VoiceResult.Success("Increased quantity of ${existing.name} by ${command.quantity} (Total: $newQty)")
                        )
                    } else {
                        if (newItems != null) {
                            _voiceResultEvent.emit(VoiceResult.Error("Inventory changed. Please review the voice command again."))
                        } else {
                            _voiceResultEvent.emit(VoiceResult.OpenQuickAdd(command.itemName, command.quantity))
                        }
                    }
                }
                VoiceAction.DEDUCT -> {
                    val existing = findInventoryItem(command.itemName)
                    if (existing != null) {
                        val newQty = (existing.quantity - command.quantity).coerceAtLeast(0)
                        groceryRepository.updateQuantityFromVoiceDeduct(existing.id, newQty)

                        if (newQty <= 0) {

                            _voiceResultEvent.emit(
                                VoiceResult.Success("Used all ${existing.name}! Quantity is now 0.")
                            )
                        } else {

                            _voiceResultEvent.emit(
                                VoiceResult.Success("Deducted ${command.quantity} ${existing.name}. Remaining: $newQty")
                            )
                        }
                    } else {
                        _voiceResultEvent.emit(
                            VoiceResult.Error("Could not find \"${command.itemName}\" in inventory to deduct.")
                        )
                    }
                }
                VoiceAction.RECIPE -> {
                    _voiceResultEvent.emit(
                        VoiceResult.OpenRecipes(command.itemName)
                    )
                }
                VoiceAction.UNKNOWN -> {
                    _voiceResultEvent.emit(
                        VoiceResult.Error("Command not recognized. Try: \"add milk\", \"used 2 eggs\", or \"suggest recipe with tomatoes\"")
                    )
                }
            }
        }
    }

    fun processShoppingVoiceCommandOptions(commandTexts: List<String>, assignedTo: String = "Household") {
        viewModelScope.launch {
            val review = VoiceCommandReview.prepare(commandTexts, shopping = true)
            if (!review.canConfirm || assignedTo !in ShoppingVoiceMerge.assignees) {
                _voiceResultEvent.emit(VoiceResult.Error(review.error ?: "Choose Me only or Household."))
                return@launch
            }
            val parsedCommands = review.commands
            try {
                val additions = parsedCommands.filter { it.action == VoiceAction.ADD }
                    .map { it.copy(itemName = cleanShoppingItemName(it.itemName)) }
                if (additions.isNotEmpty()) shoppingListRepository.addVoiceItems(additions, assignedTo)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _voiceResultEvent.emit(VoiceResult.Error("Couldn’t save this shopping command. No items were added. Please try again."))
                return@launch
            }

            parsedCommands.forEach { command ->
                when (command.action) {
                    VoiceAction.ADD -> {
                        val itemName = cleanShoppingItemName(command.itemName)
                        _voiceResultEvent.emit(
                            VoiceResult.Success("Added ${command.quantity} $itemName for $assignedTo.")
                        )
                    }
                    VoiceAction.RECIPE -> {
                        _voiceResultEvent.emit(VoiceResult.OpenRecipes(command.itemName))
                    }
                    VoiceAction.DEDUCT -> {
                        _voiceResultEvent.emit(
                            VoiceResult.Error("Shopping voice can add list items. Try: \"add bread to shopping list\"")
                        )
                    }
                    VoiceAction.UNKNOWN -> {
                        _voiceResultEvent.emit(
                            VoiceResult.Error("Command not recognized. Try: \"add bread to shopping list\"")
                        )
                    }
                }
            }
        }
    }

    private fun cleanShoppingItemName(itemName: String): String {
        return itemName
            .removeSuffix("to shopping list")
            .removeSuffix("to my shopping list")
            .removeSuffix("in shopping list")
            .removeSuffix("in my shopping list")
            .removeSuffix("shopping list")
            .trim()
            .ifBlank { itemName.trim() }
    }

    private suspend fun findInventoryItem(itemName: String): GroceryItem? {
        return groceryRepository.getItemByName(itemName)
            ?: groceryRepository.findBestMatchingItem(itemName)
    }
}

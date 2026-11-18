package com.xmum.smartgrocery.utils

import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import java.text.Normalizer
import java.util.Locale

/** Read-only planning shared by the review and the transactional Shopping voice write. */
object ShoppingVoiceMerge {
    const val VOICE_SOURCE = "Voice"
    val assignees = listOf("Me", "Household")

    data class Preview(val itemName: String, val added: Int, val previous: Int?, val total: Int)

    fun merge(items: List<ShoppingListItem>, command: VoiceCommand, assignedTo: String): ShoppingListItem {
        require(assignedTo in assignees) { "Choose Me only or Household." }
        require(command.action == VoiceAction.ADD && command.quantity > 0 && command.itemName.isNotBlank()) {
            "Review all item names and quantities before adding."
        }
        val name = normalizedName(command.itemName)
        require(name.isNotEmpty()) { "Enter an item name." }
        val existing = items.filter {
            !it.isChecked && it.assignedTo == assignedTo && normalizedName(it.itemName) == name && countUnit(it) != null
        }.sortedWith(compareBy<ShoppingListItem> { it.sourceItemId != null }.thenBy { it.id }).firstOrNull()
        if (existing == null) return ShoppingListItem(itemName = command.itemName.trim(), category = GroceryCategories.suggest(command.itemName),
            quantityNeeded = command.quantity, estimatedUnitPrice = 0.0, addedBy = VOICE_SOURCE, assignedTo = assignedTo)
        val total = existing.quantityNeeded.toLong() + command.quantity
        require(total <= Int.MAX_VALUE) { "The combined quantity is too large. Edit your command." }
        val unit = countUnit(existing).orEmpty()
        return existing.copy(quantityNeeded = total.toInt(),
            quantityLabel = if (unit.isEmpty()) "" else "$total $unit",
            // A voice-confirmed plan must not be reset to the automatic low-stock suggestion.
            addedBy = if (existing.sourceItemId != null) VOICE_SOURCE else existing.addedBy,
            updatedAt = System.currentTimeMillis())
    }

    fun preview(items: List<ShoppingListItem>, commands: List<VoiceCommand>, assignedTo: String): List<Preview> {
        val pending = items.toMutableList()
        return commands.filter { it.action == VoiceAction.ADD }.mapIndexed { index, command ->
            val result = merge(pending, command, assignedTo)
            val previous = pending.firstOrNull { it.id == result.id && result.id != 0 }
            val staged = if (result.id == 0) result.copy(id = -index - 1) else result
            if (previous == null) pending += staged else pending[pending.indexOf(previous)] = staged
            Preview(result.itemName, command.quantity, previous?.quantityNeeded, result.quantityNeeded)
        }
    }

    internal fun normalizedName(value: String): String {
        val plain = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("\\([^)]*\\)"), " ").lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
        val alias = VoiceParser.normalizeItemName(plain)
        // Conservative whole-word aliases, never fuzzy matches such as egg vs eggplant.
        val singular = mapOf("eggs" to "egg", "apples" to "apple", "bananas" to "banana",
            "tomatoes" to "tomato", "potatoes" to "potato", "carrots" to "carrot", "onions" to "onion",
            "peppers" to "pepper", "wings" to "wing", "yogurts" to "yogurt", "yoghurts" to "yogurt",
            "yoghurt" to "yogurt", "oranges" to "orange", "lemons" to "lemon", "mushrooms" to "mushroom")
        return alias.split(' ').joinToString(" ") { singular[it] ?: it }
    }

    /** Voice quantities are whole item/package counts; never add them to grams or millilitres. */
    private fun countUnit(item: ShoppingListItem): String? {
        if (item.quantityLabel.isBlank()) return ""
        val label = Regex("^(\\d+)\\s*([\\p{L}]+)?$").matchEntire(item.quantityLabel.trim().lowercase(Locale.ROOT)) ?: return null
        if (label.groupValues[1].toLongOrNull() != item.quantityNeeded.toLong()) return null
        return when (label.groupValues[2]) {
            "" -> ""
            "pc", "pcs", "piece", "pieces", "个", "颗", "只", "biji", "ekor" -> "pcs"
            "bottle", "bottles", "瓶", "botol" -> "bottles"
            "pack", "packs", "packet", "packets", "包", "bungkus", "pek", "paket" -> "packs"
            "carton", "cartons" -> "cartons"
            "box", "boxes", "盒", "箱", "kotak" -> "boxes"
            "can", "cans", "罐", "tin" -> "cans"
            "bag", "bags", "袋" -> "bags"
            "stalk", "stalks", "根", "棵", "batang" -> "stalks"
            "clove", "cloves", "瓣", "ulas" -> "cloves"
            "slice", "slices", "片", "keping" -> "slices"
            else -> null
        }
    }
}

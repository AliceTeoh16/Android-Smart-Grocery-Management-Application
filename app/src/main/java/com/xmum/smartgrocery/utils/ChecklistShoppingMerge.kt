package com.xmum.smartgrocery.utils

import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/** Recipe measurements accumulate independently of recipe history and shopping ownership. */
object ChecklistShoppingMerge {
    const val SOURCE = "AI Checklist"
    data class Addition(val itemName: String, val category: String, val quantityLabel: String)
    private data class Amount(val value: BigDecimal, val unit: String)

    fun pendingNames(items: List<ShoppingListItem>): Set<String> = items.asSequence()
        .filter { !it.isChecked && it.quantityNeeded > 0 }
        .map { ShoppingVoiceMerge.normalizedName(it.itemName) }.filter { it.isNotBlank() }.toSet()

    fun isPending(itemName: String, pendingNames: Set<String>): Boolean =
        ShoppingVoiceMerge.normalizedName(itemName) in pendingNames

    /** Plan only; the DAO re-reads and applies this result inside one Room transaction. */
    fun merge(items: List<ShoppingListItem>, addition: Addition, assignedTo: String,
        now: Long = System.currentTimeMillis()): ShoppingListItem {
        require(addition.itemName.isNotBlank() && assignedTo.isNotBlank()) { "Choose an item and shopping list." }
        val key = ShoppingVoiceMerge.normalizedName(addition.itemName)
        require(key.isNotBlank()) { "Enter an item name." }
        val added = parse(addition.quantityLabel)
        val match = items.asSequence().filter {
            !it.isChecked && it.assignedTo == assignedTo && ShoppingVoiceMerge.normalizedName(it.itemName) == key
        }.sortedWith(compareBy<ShoppingListItem> { it.sourceItemId != null }.thenBy { it.id })
            .mapNotNull { item ->
                val previous = parse(item.quantityLabel.ifBlank { item.quantityNeeded.toString() })
                if (added != null && previous != null && previous.unit == added.unit) item to previous else null
            }.firstOrNull()
        if (match != null && added != null) {
            val (item, previous) = match
            val total = previous.value + added.value
            return item.copy(quantityNeeded = total.stockCount(), quantityLabel = "${total.label()} ${added.unit}",
                addedBy = if (item.sourceItemId != null) SOURCE else item.addedBy, updatedAt = now)
        }
        // Different units (e.g. grams vs packs) cannot be converted without package-size data.
        return ShoppingListItem(itemName = addition.itemName.trim(), category = addition.category,
            quantityNeeded = (added?.value ?: firstNumber(addition.quantityLabel) ?: BigDecimal.ONE).stockCount(),
            quantityLabel = addition.quantityLabel.trim(), estimatedUnitPrice = 0.0,
            addedBy = SOURCE, assignedTo = assignedTo, createdAt = now, updatedAt = now)
    }

    private fun parse(label: String): Amount? {
        val fractions = mapOf('½' to " 1/2", '¼' to " 1/4", '¾' to " 3/4", '⅓' to " 1/3", '⅔' to " 2/3")
        val cleaned = label.replace(Regex("\\s*\\(Fully Covered\\)", RegexOption.IGNORE_CASE), "")
            .map { fractions[it] ?: it.toString() }.joinToString("").trim()
        val match = Regex("""^(?:(\d+)\s+)?(\d+(?:\.\d+)?(?:/\d+(?:\.\d+)?)?|\.\d+)\s*([\p{L}][\p{L}\s.-]*)?$""").matchEntire(cleaned) ?: return null
        val parts = match.groupValues[2].split('/')
        val numerator = parts[0].toBigDecimalOrNull() ?: return null
        val fraction = if (parts.size == 2) {
            val denominator = parts[1].toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return null
            numerator.divide(denominator, MathContext.DECIMAL64)
        } else numerator
        var amount = (match.groupValues[1].toBigDecimalOrNull() ?: BigDecimal.ZERO) + fraction
        if (amount <= BigDecimal.ZERO) return null
        val raw = match.groupValues[3].lowercase(Locale.ROOT).trim().trimEnd('.').replace(Regex("\\s+"), " ")
        val unit = when (raw) {
            "kg", "kilogram", "kilograms", "公斤", "千克" -> { amount *= BigDecimal(1000); "g" }
            "g", "gram", "grams", "克" -> "g"
            "l", "liter", "liters", "litre", "litres", "升" -> { amount *= BigDecimal(1000); "ml" }
            "ml", "milliliter", "milliliters", "millilitre", "millilitres", "毫升" -> "ml"
            "", "unit", "units", "pc", "pcs", "piece", "pieces", "个", "颗", "只", "biji", "ekor" -> "pcs"
            "pack", "packs", "packet", "packets", "包", "bungkus", "pek", "paket" -> "packs"
            "bottle", "bottles", "瓶", "botol" -> "bottles"
            "tbsp", "tablespoon", "tablespoons", "汤匙" -> "tbsp"
            "tsp", "teaspoon", "teaspoons", "茶匙" -> "tsp"
            "clove", "cloves", "瓣", "ulas" -> "cloves"
            "stalk", "stalks", "根", "棵", "条", "batang" -> "stalks"
            "slice", "slices", "片", "keping" -> "slices"
            "cup", "cups", "杯" -> "cups"
            else -> raw
        }
        return Amount(amount, unit)
    }
    private fun firstNumber(label: String): BigDecimal? = Regex("""\d+(?:\.\d+)?""").find(label)?.value?.toBigDecimalOrNull()
    private fun BigDecimal.label(): String = stripTrailingZeros().toPlainString()
    private fun BigDecimal.stockCount(): Int {
        val count = setScale(0, RoundingMode.CEILING).max(BigDecimal.ONE)
        require(count <= Int.MAX_VALUE.toBigDecimal()) { "The combined quantity is too large." }
        return count.intValueExact()
    }
}

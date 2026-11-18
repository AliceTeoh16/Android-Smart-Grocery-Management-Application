package com.xmum.smartgrocery.utils

import java.text.Normalizer
import java.util.Locale

data class IngredientTextCandidate(
    val name: String, val category: String, val quantity: Int = 1,
    val quantityDetected: Boolean = false, val selected: Boolean = true, val source: String = name,
)

/** Conservative local OCR parsing. Weights, prices, dates and nutrition values are never item counts. */
object IngredientTextParser {
    val categories = listOf("Dairy", "Fruits & Vegetables", "Meat & Seafood", "Bakery", "Pantry", "Beverages", "Snacks", "Other")
    private val foods = mapOf(
        "Dairy" to "milk|eggs?|yog[hu]?urt|cheese|butter|cream|susu|telur|keju|牛奶|鸡蛋|雞蛋|酸奶|奶酪|黄油|黃油",
        "Fruits & Vegetables" to "apples?|bananas?|oranges?|tomato(?:es)?|carrots?|onions?|garlic|potato(?:es)?|lettuce|spinach|broccoli|cabbage|berries|avocados?|buah|pisang|epal|tomato|lobak|bawang|kentang|sayur|苹果|蘋果|香蕉|橙|番茄|西红柿|胡萝卜|胡蘿蔔|洋葱|洋蔥|土豆|马铃薯|蒜|蔬菜",
        "Meat & Seafood" to "chicken|beef|pork|lamb|fish|salmon|tuna|prawns?|shrimp|seafood|ayam|daging|ikan|udang|鸡肉|雞肉|牛肉|猪肉|豬肉|羊肉|鱼|魚|虾|蝦",
        "Bakery" to "bread|buns?|bagels?|croissants?|roti|面包|麵包|馒头|饅頭",
        "Pantry" to "rice|pasta|noodles?|flour|sugar|salt|oil|sauce|beans?|lentils?|oats?|beras|mi|mee|tepung|gula|garam|minyak|kicap|大米|米饭|面条|麵條|面粉|糖|盐|鹽|食用油|酱油|醬油",
        "Beverages" to "juices?|coffee|tea|water|soda|jus|kopi|teh|minuman|果汁|咖啡|茶|饮料|飲料",
        "Snacks" to "biscuits?|cookies?|crackers?|chips|chocolates?|nuts?|biskut|coklat|kacang|饼干|餅乾|巧克力|薯片|坚果|堅果",
    )
    private val categoryPatterns = foods.mapValues { (_, words) ->
        Regex(words.split('|').joinToString("|") { if (it.any { c -> c in '\u4e00'..'\u9fff' }) it else "\\b(?:$it)\\b" }, RegexOption.IGNORE_CASE)
    }
    private val ignored = Regex("^(?:nutrition|nutritional|nutrition facts|energy|calories|protein|carbohydrate|carbs|fat|saturated|sugars|sodium|cholesterol|fibre|fiber|servings?|serving size|ingredients?[:：]|allergen|contains[:：]|best before|use by|expiry|exp[:： ]|manufactur|packed on|product of|made in|net (?:weight|volume|content)|nett? wt|total|subtotal|tax|gst|sst|change|cash|receipt|invoice|thank you|www\\.|https?://|barcode|sku|product label test|berat bersih|isi padu|tarikh|jumlah|营养|營養|配料|成分|净重|淨重|净含量|保质期|有效期|生产日期|總計|总计)", RegexOption.IGNORE_CASE)
    private val units = "(?:(?:pieces?|pcs?|packets?|packs?|bottles?|cartons?|boxes|box|bags?|cans?|biji|botol|kotak|bungkus|pek|tin)(?![A-Za-z])|个|個|颗|顆|枚|瓶|盒|包|袋|罐|只|支)"
    private val measurement = Regex("\\d+(?:[.,]\\d+)?\\s*(?:kg|mg|g|ml|l|litres?|liters?|grams?|kcal|kj|%|公斤|千克|克|毫升|升)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val separateCount = Regex("^(?:(?:qty|quantity|kuantiti|数量|數量)\\s*[:：x×]?\\s*)?(\\d+)\\s*$units?$", RegexOption.IGNORE_CASE)

    fun parse(rawLines: List<String>): List<IngredientTextCandidate> {
        val candidates = mutableListOf<IngredientTextCandidate>()
        rawLines.take(400).forEach { raw ->
            var line = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().trimStart('•', '·', '-', '*').trim()
            if (line.isBlank() || line.length > 140 || ignored.containsMatchIn(line)) return@forEach
            if (Regex("^(?:RM|MYR|\\$)\\s*\\d|^\\d+[.,]\\d{2}$|^\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}$", RegexOption.IGNORE_CASE).containsMatchIn(line)) return@forEach
            // A nearby standalone pack count belongs to the preceding candidate, never to a new item.
            separateCount.matchEntire(line)?.let { count ->
                val qty = count.groupValues[1].toIntOrNull()
                if (qty != null && qty in 1..999 && candidates.isNotEmpty()) {
                    candidates[candidates.lastIndex] = candidates.last().copy(quantity = qty, quantityDetected = true)
                }
                return@forEach
            }
            line = line.replace(Regex("\\s+(?:RM|MYR|\\$)\\s*\\d+(?:[.,]\\d{2})?\\s*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+\\d+[.,]\\d{2}\\s*$"), "") // A receipt line's trailing price.
            line = line.replace(measurement, "").trim().replace(Regex("\\s{2,}"), " ")
            if (line.isBlank() || line.none { it.isLetter() }) return@forEach
            var quantity: Int? = null
            val prefix = Regex("^(\\d+)\\s*(?:[x×]\\s*)?(?:$units\\s*(?:of\\s+)?)?(.+)$", RegexOption.IGNORE_CASE).matchEntire(line)
            val suffix = Regex("^(.+?)\\s*(?:[x×]\\s*|(?:qty|quantity|kuantiti|数量|數量)\\s*[:：]?\\s*)(\\d+)\\s*(?:$units)?$", RegexOption.IGNORE_CASE).matchEntire(line)
            val countedSuffix = Regex("^(.+?)\\s+(\\d+)\\s*(?:$units)?$", RegexOption.IGNORE_CASE).matchEntire(line)
                ?: Regex("^(.+?)(\\d+)\\s*$units$", RegexOption.IGNORE_CASE).matchEntire(line)
            when {
                prefix != null -> { quantity = prefix.groupValues[1].toIntOrNull(); line = prefix.groupValues[2] }
                suffix != null -> { quantity = suffix.groupValues[2].toIntOrNull(); line = suffix.groupValues[1] }
                countedSuffix != null -> { quantity = countedSuffix.groupValues[2].toIntOrNull(); line = countedSuffix.groupValues[1] }
            }
            val name = line.trim().trimEnd(':', '：', '-', '×').trim().take(100)
            if ((name.length < 2 && name.none { it in '\u4e00'..'\u9fff' }) || name.none { it.isLetter() } || Regex("\\d{6,}").containsMatchIn(name)) return@forEach
            val category = categoryPatterns.entries.firstOrNull { it.value.containsMatchIn(name) }?.key ?: "Other"
            val detected = quantity != null && quantity in 1..999
            val countMentioned = prefix != null || suffix != null || countedSuffix != null
            val invalidCount = Regex("(?:[x×]\\s*|\\bqty\\s*[:：]?\\s*)-?\\d+[.,]\\d+|[x×]\\s*-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(name)
            val candidate = IngredientTextCandidate(name, category, if (detected) quantity!! else 1,
                detected, category != "Other" && (!countMentioned || detected) && !invalidCount, raw.trim().take(140))
            val duplicate = candidates.indexOfFirst { it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) &&
                (it.quantity == candidate.quantity || !it.quantityDetected || !candidate.quantityDetected) }
            if (duplicate >= 0) {
                if (candidate.quantityDetected) candidates[duplicate] = candidate
            } else if (candidates.size < 50) candidates += candidate
        }
        return candidates
    }
}

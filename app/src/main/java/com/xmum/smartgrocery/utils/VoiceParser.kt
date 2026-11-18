package com.xmum.smartgrocery.utils

import java.util.Locale

enum class VoiceAction {
    ADD,
    DEDUCT,
    RECIPE,
    UNKNOWN
}

data class VoiceCommand(
    val action: VoiceAction,
    val quantity: Int,
    val itemName: String
)

object VoiceParser {

    /**
     * Parses simple voice sentences.
     * Examples:
     * - "add 3 apples" -> ADD, 3, "apples"
     * - "used 2 eggs" -> DEDUCT, 2, "eggs"
     * - "deduct 1 bread" -> DEDUCT, 1, "bread"
     * - "add eggs" -> ADD, 1, "eggs"
     * - "used milk" -> DEDUCT, 1, "milk"
     * - "remove 4 bananas" -> DEDUCT, 4, "bananas"
     * - "suggest a dinner recipe using my expiring tomatoes" -> RECIPE
     */
    fun parse(text: String?): VoiceCommand {
        return parseAll(text).firstOrNull() ?: VoiceCommand(VoiceAction.UNKNOWN, 0, text.orEmpty())
    }

    fun parseAll(text: String?): List<VoiceCommand> {
        if (text.isNullOrBlank()) {
            return listOf(VoiceCommand(VoiceAction.UNKNOWN, 0, ""))
        }

        val cleanText = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        if (containsChinese(cleanText)) {
            return parseChinese(cleanText)
        }

        val addActions = setOf("add", "buy", "purchase", "plus", "tambah", "tambahkan", "beli", "belikan", "membeli", "menambah", "masukkan")
        val deductActions = setOf("use", "used", "deduct", "remove", "minus", "eat", "ate", "consumed", "guna", "gunakan", "pakai", "kurangkan", "tolak", "buang", "makan")
        val actionWords = (addActions + deductActions).joinToString("|")
        val actionPattern = Regex("""^(?:(?:please|i|have|has|had|already|just|want|to|sila|tolong|saya|aku|nak|mahu|ingin|dah|sudah|telah|baru|hendak|boleh)\s+)*($actionWords)\b\s*""")
        val recipeKeywords = listOf("suggest", "recommend", "recipe", "cook", "meal", "dinner", "lunch", "breakfast", "cadangkan", "resipi", "resepi", "masak")
        if (!actionPattern.containsMatchIn(cleanText) && recipeKeywords.any { Regex("""\b\Q$it\E\b""").containsMatchIn(cleanText) }) {
            return listOf(VoiceCommand(VoiceAction.RECIPE, 0, cleanText))
        }

        // Recognition may return words instead of digits. Quantities remain whole item counts.
        val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen", "twenty")
        var normalized = MalayVoiceSupport.normalizeCounts(cleanText.trimEnd('.', '!', '?'))
        numbers.forEachIndexed { number, word ->
            normalized = normalized.replace(Regex("""\b$word\b"""), number.toString())
        }
        if (Regex("""(?:-\s*\d|\d[.,]\d|\b\d+\s+(?:point|perpuluhan|koma)\s+\d+\b|\bnegatif\b|\b(?:setengah|separuh)\b)""").containsMatchIn(normalized)) {
            return listOf(VoiceCommand(VoiceAction.UNKNOWN, 0, cleanText))
        }

        val parts = normalized.split(Regex("""\s*[,;]\s*|\s+(?:and|dan|serta|kemudian|lepas itu|selepas itu)(?:\s+|$)"""))
        var previousAction = VoiceAction.UNKNOWN
        return parts.map { part ->
            val actionMatch = actionPattern.find(part.trim())
            val action = when (actionMatch?.groupValues?.get(1)) {
                in addActions -> VoiceAction.ADD
                in deductActions -> VoiceAction.DEDUCT
                else -> previousAction
            }
            previousAction = action
            var remaining = if (actionMatch == null) part.trim() else part.trim().substring(actionMatch.range.last + 1).trim()
            remaining = remaining.replace(Regex("""\s+(?:to|in)\s+(?:my\s+)?shopping list$|\s+shopping list$"""), "").trim()
            remaining = remaining.replace(Regex("""\s+(?:ke(?: dalam)?|dalam)\s+(?:senarai (?:beli-belah|membeli-belah|belian|belanja)|inventori|stok)$"""), "").trim()
            val quantityMatch = Regex("""^(\d+|a|an)\s+(.+)$""").matchEntire(remaining)
            val quantity = when (val raw = quantityMatch?.groupValues?.get(1)) {
                null, "a", "an" -> 1
                else -> raw.toIntOrNull() ?: 0
            }
            val rawName = quantityMatch?.groupValues?.get(2) ?: remaining
            val itemName = normalizeItemName(rawName.replace(
                Regex("""^(?:bottles?|cartons?|packs?|packets?|bags?|cans?|pieces?|pcs|boxes?|${MalayVoiceSupport.classifiers})\s+(?:(?:of|of the)\s+)?"""), ""
            ).trim())
            // Reject incomplete/ambiguous clauses instead of applying only part of a sentence.
            if (action == VoiceAction.UNKNOWN || itemName.isBlank() || itemName.all { it.isDigit() } ||
                Regex("""\b\d+\b""").containsMatchIn(itemName)) {
                VoiceCommand(VoiceAction.UNKNOWN, 0, part)
            } else {
                VoiceCommand(action, quantity, itemName)
            }
        }
    }

    private fun parseChinese(text: String): List<VoiceCommand> {
        // Preserve list boundaries instead of joining all items into one name.
        val cleanText = simplifyChinese(text).replace(Regex("\\s+"), "")
            .trimEnd('。', '.', '！', '!', '？', '?')
            .replace(Regex("(?:添加到|加入|放到|到|进)?(?:我的)?(?:购物清单|购物列表|库存)$"), "")
        val addKeywords = listOf("添加", "加入", "新增", "购买", "买", "加")
        val deductKeywords = listOf("使用", "用掉", "用了", "扣除", "减少", "吃掉", "吃了", "消耗", "减去", "减", "用", "吃")
        val actions = (addKeywords + deductKeywords).joinToString("|")
        val politePrefix = Regex("^(?:请|帮我|我|已经|刚刚|刚|想要|想|要|再|也|把|了)+")
        val firstClause = cleanText.replace(politePrefix, "")
        val recipeKeywords = listOf("食谱", "菜谱", "料理", "推荐", "建议", "煮", "做饭", "晚餐", "午餐", "早餐")
        if (!Regex("^(?:$actions)").containsMatchIn(firstClause) && recipeKeywords.any { cleanText.contains(it) }) {
            return listOf(VoiceCommand(VoiceAction.RECIPE, 0, cleanText))
        }
        // Item counts must be positive whole numbers. Never silently turn a fraction into one.
        if (Regex("[-－负]\\s*[0-9零〇一二两三四五六七八九十]|[0-9零〇一二两三四五六七八九十][.点][0-9零〇一二两三四五六七八九十]|半(?:个|瓶|只|盒|包|斤)")
                .containsMatchIn(cleanText)) {
            return listOf(VoiceCommand(VoiceAction.UNKNOWN, 0, cleanText))
        }
        val number = "(?:[0-9]+|[零〇一二两三四五六七八九十百千万]+)"
        val measure = "[个颗粒只盒瓶包袋斤份条块罐杯支枚棵根片碗箱]"
        // Recognizers sometimes omit punctuation: 两瓶牛奶三个鸡蛋 still has a clear count boundary.
        fun splitItems(part: String): List<String> {
            val clause = part.replace(politePrefix, "")
            val initialAction = (addKeywords + deductKeywords).firstOrNull { clause.startsWith(it) }.orEmpty()
            val body = clause.removePrefix(initialAction).replace(politePrefix, "")
            val cuts = (Regex("$number$measure").findAll(body).filter { match ->
                val before = body.substring(0, match.range.first)
                before.isNotEmpty() && before.last().isLetter() && !Regex("(?:$actions)(?:了)?$").containsMatchIn(before)
            }.map { it.range.first } + Regex("(?:$actions)(?:了)?$number").findAll(body)
                .filter { it.range.first > 0 }.map { it.range.first }).distinct().sorted().toList()
            val boundaries = listOf(0) + cuts + body.length
            return boundaries.zipWithNext { start, end -> body.substring(start, end) }
                .mapIndexed { index, item -> if (index == 0) initialAction + item else item }
        }
        val parts = cleanText.split(Regex("(?:[，,、；;。]|以及|还有|然后|接着|并且|和|跟|及|再(?=(?:$actions)))+"))
            .flatMap { splitItems(it) }
        var previousAction = VoiceAction.UNKNOWN
        return parts.map { part ->
            val clause = part.replace(politePrefix, "")
            val actionKeyword = (addKeywords + deductKeywords).firstOrNull { clause.startsWith(it) }
            val action = when (actionKeyword) {
                in addKeywords -> VoiceAction.ADD
                in deductKeywords -> VoiceAction.DEDUCT
                else -> previousAction
            }
            previousAction = action
            val remaining = (if (actionKeyword == null) clause else clause.removePrefix(actionKeyword))
                .replace(politePrefix, "").removeSuffix("了")
            val namedFood = listOf("三文鱼", "四季豆", "九层塔").any { remaining.startsWith(it) }
            val quantityMatch = if (namedFood) null else Regex("^$number").find(remaining)
            val quantity = quantityMatch?.value?.let { parseQuantity(it) } ?: 1
            val rawName = remaining.substring(quantityMatch?.range?.last?.plus(1) ?: 0)
            val itemName = (if (quantityMatch != null) rawName.replace(Regex("^$measure"), "") else rawName).trim()
            if (action == VoiceAction.UNKNOWN || itemName.isBlank() ||
                Regex("^[0-9零〇一二两三四五六七八九十百千万]+$|$number$measure").containsMatchIn(itemName)) {
                VoiceCommand(VoiceAction.UNKNOWN, 0, part)
            } else {
                VoiceCommand(action, quantity, normalizeItemName(itemName))
            }
        }
    }

    private fun simplifyChinese(text: String): String {
        val traditional = "購買雞蛋兩個顆減條塊魚蘿蔔麵鹽醬飯庫單葉"
        val simplified = "购买鸡蛋两个颗减条块鱼萝卜面盐酱饭库单叶"
        return text.map { character ->
            val index = traditional.indexOf(character)
            if (index < 0) character else simplified[index]
        }.joinToString("")
    }

    private fun containsChinese(text: String): Boolean {
        return text.any { it in '\u4e00'..'\u9fff' }
    }

    private fun parseQuantity(text: String): Int {
        if (text.all { it.isDigit() }) return text.toIntOrNull() ?: 0
        val values = mapOf(
            '零' to 0,
            '〇' to 0,
            '一' to 1,
            '二' to 2,
            '两' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '七' to 7,
            '八' to 8,
            '九' to 9
        )

        if (text.length == 1) return values[text[0]] ?: if (text == "十") 10 else 0
        if (Regex("[一二两三四五六七八九]?十[一二三四五六七八九]?").matches(text)) {
            val parts = text.split("十")
            val tens = parts.firstOrNull()?.firstOrNull()?.let { values[it] } ?: 1
            val ones = parts.getOrNull(1)?.firstOrNull()?.let { values[it] } ?: 0
            return tens * 10 + ones
        }

        return 0
    }

    internal fun normalizeItemName(itemName: String): String {
        val normalized = itemName.trim()
        val itemAliases = mapOf(
            "susu" to "milk",
            "telur" to "eggs",
            "sayap ayam" to "chicken wing",
            "roti" to "bread",
            "daging lembu" to "beef",
            "daging ayam" to "chicken",
            "ayam" to "chicken",
            "beras" to "rice",
            "minyak masak" to "cooking oil",
            "kicap" to "soy sauce",
            "garam" to "salt",
            "gula" to "sugar",
            "lobak merah" to "carrot",
            "kentang" to "potato",
            "bawang putih" to "garlic",
            "bawang" to "onion",
            "mi" to "noodles",
            "mee" to "noodles",
            "ikan" to "fish",
            "yogurt greek" to "greek yogurt",
            "牛奶" to "milk",
            "鸡蛋" to "eggs",
            "鸡翅" to "chicken wing",
            "三文鱼" to "salmon",
            "酸奶" to "yogurt",
            "希腊酸奶" to "greek yogurt",
            "大米" to "rice",
            "米" to "rice",
            "食用油" to "cooking oil",
            "酱油" to "soy sauce",
            "盐" to "salt",
            "面条" to "noodles",
            "蛋" to "eggs",
            "面包" to "bread",
            "牛肉" to "beef",
            "番茄" to "tomato",
            "西红柿" to "tomato",
            "鳄梨" to "avocado",
            "牛油果" to "avocado",
            "胡萝卜" to "carrot",
            "萝卜" to "carrot",
            "土豆" to "potato",
            "马铃薯" to "potato",
            "鱼" to "fish",
            "鱼肉" to "fish",
            "肉" to "meat",
            "蔬菜" to "vegetable",
            "青菜" to "vegetable"
        )

        return itemAliases[normalized] ?: normalized
    }
}

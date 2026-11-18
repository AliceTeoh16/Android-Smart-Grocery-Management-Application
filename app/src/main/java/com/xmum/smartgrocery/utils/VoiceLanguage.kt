package com.xmum.smartgrocery.utils

enum class VoiceLanguage(val tag: String, val label: String) {
    ENGLISH("en-US", "English"), MALAY("ms-MY", "Bahasa Melayu"), CHINESE("zh-CN", "中文 (Mandarin)");

    companion object {
        val supportedTags: List<String> get() = entries.map { it.tag }
        fun detected(tag: String?): VoiceLanguage? = when (tag?.substringBefore('-')?.lowercase(java.util.Locale.ROOT)) {
            "en" -> ENGLISH
            "ms" -> MALAY
            "zh", "cmn" -> CHINESE
            else -> null
        }
        fun fromTag(tag: String?): VoiceLanguage = detected(tag) ?: ENGLISH
    }
}

/** Recognition hints only: never rewrite a transcript or change a grocery item. */
object VoiceRecognitionVocabulary {
    private fun commandPhrases(language: VoiceLanguage): List<String> = when (language) {
        VoiceLanguage.ENGLISH -> listOf("add two eggs", "add 2 eggs", "used one egg", "used 1 egg",
            "buy two bottles of milk", "buy 2 bottles of milk", "add one chicken wing", "deduct one egg")
        VoiceLanguage.CHINESE -> listOf("我买了两个鸡蛋", "添加两个鸡蛋", "用了一个鸡蛋",
            "买两瓶牛奶", "买两瓶牛奶和一个鸡翅", "添加一根胡萝卜")
        VoiceLanguage.MALAY -> listOf("tambah dua biji telur", "beli dua botol susu", "guna satu biji telur",
            "saya sudah guna satu telur", "tambah satu sayap ayam", "beli dua botol susu dan satu sayap ayam")
    }

    private fun common(language: VoiceLanguage): List<String> = when (language) {
            VoiceLanguage.ENGLISH -> listOf("eggs", "milk", "Greek yogurt", "chicken wing", "bread", "rice",
                "cooking oil", "soy sauce", "salt", "carrot", "tomato", "beef", "fish", "noodles",
                "shampoo", "toilet paper", "dishwashing detergent", "coffee", "biscuits", "frozen dumplings")
            VoiceLanguage.CHINESE -> listOf("鸡蛋", "牛奶", "希腊酸奶", "鸡翅", "面包", "大米", "食用油",
                "酱油", "盐", "胡萝卜", "番茄", "牛肉", "鱼", "面条", "添加", "用掉", "购买",
                "洗发水", "卫生纸", "洗洁精", "咖啡", "饼干", "冷冻饺子")
            VoiceLanguage.MALAY -> listOf("telur", "susu", "yogurt", "sayap ayam", "roti", "beras", "minyak masak",
                "kicap", "garam", "lobak merah", "tomato", "daging lembu", "ikan", "mi", "tambah", "guna", "beli",
                "syampu", "tisu tandas", "pencuci pinggan", "kopi", "biskut", "ais krim")
        }

    fun build(itemNames: List<String>, language: VoiceLanguage): ArrayList<String> =
        buildWithCommon(itemNames, common(language) + commandPhrases(language))

    fun multilingual(itemNames: List<String>): ArrayList<String> = buildWithCommon(itemNames,
        VoiceLanguage.entries.flatMap { common(it) + commandPhrases(it) }.distinct())

    private fun buildWithCommon(itemNames: List<String>, common: List<String>): ArrayList<String> {
        val custom = itemNames.asSequence().flatMap { name ->
            (listOf(name) + name.split(Regex("[()（）·/]"))).asSequence()
        }.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 80 }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }.take(100 - common.size).toList()
        return ArrayList((custom + common).distinctBy { it.lowercase(java.util.Locale.ROOT) })
    }
}

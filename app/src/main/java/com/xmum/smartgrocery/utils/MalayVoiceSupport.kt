package com.xmum.smartgrocery.utils

/** Whole item counts only. Item aliases are resolved after command parsing, never in the transcript. */
internal object MalayVoiceSupport {
    const val classifiers = "botol|kotak|bungkus|paket|pek|tin|biji|ekor|keping|batang|ulas|ikat|papan"
    private val digits = listOf("kosong", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "lapan", "sembilan")
    private val unit = digits.drop(1).joinToString("|")
    private val number = Regex("\\b(?:(?:$unit) puluh(?: (?:$unit))?|(?:${digits.drop(2).joinToString("|")}) belas|seratus|sebelas|sepuluh|sifar|${digits.joinToString("|")})\\b")

    fun normalizeCounts(text: String): String {
        val expanded = text.replace(Regex("\\bse($classifiers)\\b")) { "1 ${it.groupValues[1]}" }
        return expanded.replace(number) { match ->
            val words = match.value.split(' ')
            val quantity = when {
                words.size > 1 && words[1] == "puluh" -> digits.indexOf(words[0]) * 10 + (words.getOrNull(2)?.let { digits.indexOf(it) } ?: 0)
                words.size > 1 && words[1] == "belas" -> 10 + digits.indexOf(words[0])
                match.value == "sepuluh" -> 10
                match.value == "sebelas" -> 11
                match.value == "seratus" -> 100
                match.value == "sifar" -> 0
                else -> digits.indexOf(match.value)
            }
            quantity.toString()
        }
    }
}

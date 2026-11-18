package com.xmum.smartgrocery.utils

/** A read-only interpretation. Preparing this never writes inventory or shopping data. */
data class VoiceCommandReview(val transcript: String, val commands: List<VoiceCommand>, val error: String?) {
    val canConfirm: Boolean get() = error == null

    companion object {
        fun prepare(alternatives: List<String>, shopping: Boolean): VoiceCommandReview {
            val candidates = alternatives.filter { it.isNotBlank() }.map { text ->
                val commands = VoiceParser.parseAll(text)
                val error = when {
                    commands.isEmpty() || commands.any { it.action == VoiceAction.UNKNOWN || it.itemName.isBlank() } ->
                        "Couldn’t understand the whole command. Please edit it or try again."
                    commands.any { it.action != VoiceAction.RECIPE && it.quantity <= 0 } ->
                        "Use a whole-number quantity greater than zero."
                    shopping && commands.any { it.action == VoiceAction.DEDUCT } ->
                        "Shopping voice commands can add items. Use Inventory to deduct groceries."
                    else -> null
                }
                VoiceCommandReview(text.trim(), commands, error)
            }
            return candidates.firstOrNull { it.canConfirm } ?: candidates.firstOrNull()
                ?: VoiceCommandReview("", emptyList(), "No command was detected. Please try again.")
        }
    }
}

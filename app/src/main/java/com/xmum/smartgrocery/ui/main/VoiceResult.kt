package com.xmum.smartgrocery.ui.main

sealed class VoiceResult {
    data class Success(val message: String) : VoiceResult()
    data class OpenQuickAdd(val itemName: String, val quantity: Int) : VoiceResult()
    data class OpenRecipes(val prompt: String) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
}

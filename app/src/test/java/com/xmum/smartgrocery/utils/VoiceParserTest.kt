package com.xmum.smartgrocery.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceParserTest {

    @Test
    fun parse_addWithQuantity() {
        val result = VoiceParser.parse("add 3 apples")
        assertEquals(VoiceAction.ADD, result.action)
        assertEquals(3, result.quantity)
        assertEquals("apples", result.itemName)
    }

    @Test
    fun parse_deductWithQuantity() {
        val result = VoiceParser.parse("used 2 eggs")
        assertEquals(VoiceAction.DEDUCT, result.action)
        assertEquals(2, result.quantity)
        assertEquals("eggs", result.itemName)
    }

    @Test
    fun parse_addWithoutQuantity() {
        val result = VoiceParser.parse("add milk")
        assertEquals(VoiceAction.ADD, result.action)
        assertEquals(1, result.quantity)
        assertEquals("milk", result.itemName)
    }

    @Test
    fun parse_deductWithoutQuantity() {
        val result = VoiceParser.parse("remove cheese")
        assertEquals(VoiceAction.DEDUCT, result.action)
        assertEquals(1, result.quantity)
        assertEquals("cheese", result.itemName)
    }

    @Test
    fun parse_invalidCommand() {
        val result = VoiceParser.parse("hello world")
        assertEquals(VoiceAction.UNKNOWN, result.action)
        assertEquals(0, result.quantity)
        assertEquals("hello world", result.itemName)
    }

    @Test
    fun parse_recipeSuggestionCommand() {
        val result = VoiceParser.parse("Suggest a dinner recipe using my expiring tomatoes")
        assertEquals(VoiceAction.RECIPE, result.action)
        assertEquals(0, result.quantity)
        assertEquals("suggest a dinner recipe using my expiring tomatoes", result.itemName)
    }

    @Test
    fun parseAll_multiDeductCommand_returnsEachItem() {
        val result = VoiceParser.parseAll("I used 2 eggs and 1 tomato")

        assertEquals(2, result.size)
        assertEquals(VoiceAction.DEDUCT, result[0].action)
        assertEquals(2, result[0].quantity)
        assertEquals("eggs", result[0].itemName)
        assertEquals(VoiceAction.DEDUCT, result[1].action)
        assertEquals(1, result[1].quantity)
        assertEquals("tomato", result[1].itemName)
    }

    @Test
    fun parse_chineseAddCommand_mapsCommonItemName() {
        val result = VoiceParser.parse("添加两个鸡蛋")

        assertEquals(VoiceAction.ADD, result.action)
        assertEquals(2, result.quantity)
        assertEquals("eggs", result.itemName)
    }

    @Test
    fun parse_chineseDeductCommand_mapsCommonItemName() {
        val result = VoiceParser.parse("用掉一瓶牛奶")

        assertEquals(VoiceAction.DEDUCT, result.action)
        assertEquals(1, result.quantity)
        assertEquals("milk", result.itemName)
    }

    @Test
    fun parse_chineseRecipeCommand_returnsRecipeIntent() {
        val result = VoiceParser.parse("推荐番茄食谱")

        assertEquals(VoiceAction.RECIPE, result.action)
        assertEquals(0, result.quantity)
        assertEquals("推荐番茄食谱", result.itemName)
    }
}

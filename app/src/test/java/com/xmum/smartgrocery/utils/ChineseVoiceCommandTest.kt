package com.xmum.smartgrocery.utils

import org.junit.Assert.*
import org.junit.Test

class ChineseVoiceCommandTest {
    private fun review(text: String, shopping: Boolean = false) = VoiceCommandReview.prepare(listOf(text), shopping)

    @Test fun shoppingConjunctionAndMeasureWords() {
        val result = review("买两瓶牛奶和一只鸡翅", true)
        assertTrue(result.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "milk"), VoiceCommand(VoiceAction.ADD, 1, "chicken wing")), result.commands)
        assertEquals(result.commands, VoiceParser.parseAll(result.transcript))
    }

    @Test fun commaListInheritsAction() {
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "eggs"), VoiceCommand(VoiceAction.ADD, 1, "milk"),
            VoiceCommand(VoiceAction.ADD, 3, "tomato")), review("请帮我添加两个鸡蛋，一瓶牛奶、三个番茄。").commands)
    }

    @Test fun punctuationFreeListAndRepeatedAction() {
        listOf("买两瓶牛奶三个鸡蛋", "买两瓶牛奶添加三个鸡蛋", "买2瓶牛奶3个鸡蛋").forEach { text ->
            val result = review(text)
            assertTrue(text, result.canConfirm)
            assertEquals(text, listOf(VoiceCommand(VoiceAction.ADD, 2, "milk"), VoiceCommand(VoiceAction.ADD, 3, "eggs")), result.commands)
        }
    }

    @Test fun mixedActionsPreserveEachDirection() {
        val result = review("我已经用掉了一个鸡蛋，然后添加两瓶牛奶和三只鸡翅")
        assertTrue(result.toString(), result.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.DEDUCT, 1, "eggs"), VoiceCommand(VoiceAction.ADD, 2, "milk"),
            VoiceCommand(VoiceAction.ADD, 3, "chicken wing")), result.commands)
        assertFalse(review(result.transcript, true).canConfirm)
    }

    @Test fun deductionListAndChineseTens() {
        assertEquals(listOf(VoiceCommand(VoiceAction.DEDUCT, 12, "eggs"), VoiceCommand(VoiceAction.DEDUCT, 20, "tomato")),
            review("用了十二个鸡蛋以及二十个番茄").commands)
    }

    @Test fun naturalShortDeductionVerbsAndRepeatedAction() {
        assertEquals(listOf(VoiceCommand(VoiceAction.DEDUCT, 2, "eggs"), VoiceCommand(VoiceAction.DEDUCT, 1, "milk")),
            review("用两个鸡蛋再用一瓶牛奶").commands)
        assertEquals(VoiceAction.DEDUCT, review("吃一个鸡蛋").commands.single().action)
    }

    @Test fun traditionalCommonWords() {
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "eggs"), VoiceCommand(VoiceAction.ADD, 1, "milk")),
            review("購買兩個雞蛋和一瓶牛奶").commands)
    }

    @Test fun bareNamesAndFoodNamesAreNotStrippedAsClassifiersOrNumbers() {
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 1, "包菜"), VoiceCommand(VoiceAction.ADD, 1, "salmon"),
            VoiceCommand(VoiceAction.ADD, 1, "午餐肉")), review("买包菜和三文鱼和午餐肉").commands)
    }

    @Test fun optionalShoppingDestinationDoesNotBecomeAnItem() {
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "milk"), VoiceCommand(VoiceAction.ADD, 1, "eggs")),
            review("添加两瓶牛奶和一个鸡蛋到购物清单", true).commands)
    }

    @Test fun rejectsWholeCommandWithAnIncompleteClause() {
        listOf("买两瓶牛奶和", "买两瓶牛奶，三个", "买两个", "买两瓶牛奶和添加", "买两瓶牛奶和3").forEach { text ->
            assertFalse(text, review(text).canConfirm)
        }
    }

    @Test fun rejectsInvalidCountsWithoutPartiallyApplyingOtherItems() {
        listOf("买零个鸡蛋", "买0瓶牛奶和两个鸡蛋", "买负两个鸡蛋", "买-2个鸡蛋", "买1.5瓶牛奶", "买一点五瓶牛奶",
            "买半个鸡蛋", "买两瓶牛奶和0个鸡蛋", "买999999999999瓶牛奶", "买一二个鸡蛋").forEach { text ->
            assertFalse(text, review(text).canConfirm)
        }
    }
}

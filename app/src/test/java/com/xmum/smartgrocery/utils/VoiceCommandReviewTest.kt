package com.xmum.smartgrocery.utils

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandReviewTest {
    @Test fun inventoryNaturalDeduction() {
        val review = VoiceCommandReview.prepare(listOf("I already use 1 egg"), shopping = false)
        assertTrue(review.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.DEDUCT, 1, "egg")), review.commands)
    }

    @Test fun shoppingMultipleItemsAndPackaging() {
        val review = VoiceCommandReview.prepare(listOf("buy 2 bottles of milk, 1 chicken wing"), shopping = true)
        assertTrue(review.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "milk"),
            VoiceCommand(VoiceAction.ADD, 1, "chicken wing")), review.commands)
    }

    @Test fun spokenNumbersAndRepeatedActions() {
        val review = VoiceCommandReview.prepare(listOf("please add two eggs and add one bottle of milk"), false)
        assertTrue(review.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.ADD, 2, "eggs"),
            VoiceCommand(VoiceAction.ADD, 1, "milk")), review.commands)
    }

    @Test fun mixedActionsAreNotAllTreatedAsAdditions() {
        val review = VoiceCommandReview.prepare(listOf("add 2 eggs and used 1 milk"), false)
        assertTrue(review.canConfirm)
        assertEquals(VoiceAction.DEDUCT, review.commands[1].action)
        assertEquals(1, review.commands[1].quantity)
    }

    @Test fun shoppingRejectsEntireMixedCommand() {
        assertFalse(VoiceCommandReview.prepare(listOf("buy 2 eggs and used 1 milk"), true).canConfirm)
    }

    @Test fun stripsShoppingDestinationBeforeReviewAndExecution() {
        val review = VoiceCommandReview.prepare(listOf("add 2 bottles of milk to my shopping list"), true)
        assertTrue(review.canConfirm)
        assertEquals("milk", review.commands.single().itemName)
        assertEquals(review.commands, VoiceParser.parseAll(review.transcript))
    }

    @Test fun choosesOnlyOneCompleteRecognitionAlternative() {
        val review = VoiceCommandReview.prepare(listOf("hello world", "add two eggs", "add three eggs"), false)
        assertEquals("add two eggs", review.transcript)
        assertEquals(2, review.commands.single().quantity)
        assertEquals(review.commands, VoiceParser.parseAll(review.transcript))
    }

    @Test fun rejectsIncompleteOrInvalidQuantities() {
        listOf("add", "add 2", "add zero eggs", "add -2 eggs", "add 1.5 eggs", "add 999999999999 eggs",
            "add 2 eggs and", "hello world", "").forEach {
            assertFalse("Must not apply: $it", VoiceCommandReview.prepare(listOf(it), false).canConfirm)
        }
    }

    @Test fun malformedSecondClauseCannotPartiallyApply() {
        assertFalse(VoiceCommandReview.prepare(listOf("add 2 eggs and 3"), false).canConfirm)
    }

    @Test fun chineseCommandsRemainSupported() {
        val review = VoiceCommandReview.prepare(listOf("用掉一瓶牛奶"), false)
        assertTrue(review.canConfirm)
        assertEquals(listOf(VoiceCommand(VoiceAction.DEDUCT, 1, "milk")), review.commands)
    }
}

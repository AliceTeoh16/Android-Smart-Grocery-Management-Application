package com.xmum.smartgrocery.utils

import org.junit.Assert.*
import org.junit.Test

class VoiceInventoryItemDraftTest {
    private val draft = VoiceInventoryItemDraft(0, "dragonfruit", "3", expiryDate = 1800000000000L)

    @Test fun defaultsUseExistingFormSettingsAndExpiryEstimator() {
        val created = VoiceInventoryItemDraft.fromCommand(2, VoiceCommand(VoiceAction.ADD, 3, "dragonfruit"))
        assertEquals(2, created.commandIndex)
        assertEquals("3", created.quantity)
        assertEquals("Produce", created.category)
        assertEquals("0.00", created.price)
        assertEquals("2", created.threshold)
        assertEquals(ExpiryEstimator.estimateExpiryTimestamp("dragonfruit", "Produce"), created.expiryDate)
    }

    @Test fun editedDetailsProduceTheSavedItem() {
        val edited = draft.copy(name = "  Red dragonfruit  ", quantity = "5", price = "12.50",
            category = "Produce", threshold = "1", expiryDate = 1850000000000L)
        val item = edited.toGroceryItem()
        assertEquals("Red dragonfruit", item.name)
        assertEquals(5, item.quantity)
        assertEquals(12.5, item.price, 0.0)
        assertEquals("Produce", item.category)
        assertEquals(1850000000000L, item.expiryDate)
        assertEquals(1, item.threshold)
        assertEquals(0, item.id)
    }

    @Test fun invalidFieldsCannotBeSaved() {
        listOf(draft.copy(name = " "), draft.copy(quantity = "0"), draft.copy(quantity = "-1"),
            draft.copy(quantity = "1.5"), draft.copy(quantity = "2147483648"), draft.copy(price = "-1"),
            draft.copy(price = "NaN"), draft.copy(price = "Infinity"), draft.copy(price = ""),
            draft.copy(threshold = "-1"), draft.copy(threshold = "1.5"), draft.copy(category = ""),
            draft.copy(expiryDate = 0)).forEach {
            assertNotNull(it.validationError)
            try {
                it.toGroceryItem()
                fail("Invalid details must not produce a saved item")
            } catch (expected: IllegalArgumentException) { }
        }
    }

    @Test fun freeItemsAndZeroThresholdAreValid() {
        assertNull(draft.copy(price = "0", threshold = "0").validationError)
    }
}

package com.xmum.smartgrocery.utils

import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import org.junit.Assert.*
import org.junit.Test

class ShoppingVoiceMergeTest {
    private fun item(name: String = "Eggs", owner: String = "Me", quantity: Int = 1, label: String = "") =
        ShoppingListItem(id = 7, itemName = name, category = "Dairy", quantityNeeded = quantity,
            quantityLabel = label, estimatedUnitPrice = 3.5, assignedTo = owner, addedBy = "Manual", createdAt = 100)
    private fun add(name: String = "eggs", quantity: Int = 2) = VoiceCommand(VoiceAction.ADD, quantity, name)

    @Test fun sameOwnerCombinesCountAndPreservesPriceCategoryAndIdentity() {
        val existing = item("Egg (鸡蛋)")
        val merged = ShoppingVoiceMerge.merge(listOf(existing), add(), "Me")
        assertEquals(3, merged.quantityNeeded)
        assertEquals(existing.copy(quantityNeeded = 3, updatedAt = merged.updatedAt), merged)
        assertEquals(ShoppingVoiceMerge.Preview("Egg (鸡蛋)", 2, 1, 3),
            ShoppingVoiceMerge.preview(listOf(existing), listOf(add()), "Me").single())
        assertEquals(1, existing.quantityNeeded)
    }

    @Test fun chineseMalayAndEnglishAliasesMatchWithoutSubstringGuessing() {
        listOf("鸡蛋", "telur", "EGGS", "egg", "Eggs （鸡蛋）").forEach { name ->
            assertEquals(name, 3, ShoppingVoiceMerge.merge(listOf(item(name)), add(), "Me").quantityNeeded)
        }
        val eggplant = item("Eggplant")
        assertEquals(0, ShoppingVoiceMerge.merge(listOf(eggplant), add(), "Me").id)
        assertEquals(0, ShoppingVoiceMerge.merge(listOf(item("almond milk")), add("milk"), "Me").id)
    }

    @Test fun otherOwnersAndPurchasedRowsAreNeverMerged() {
        val household = item(owner = "Household")
        val purchased = item().copy(id = 8, isChecked = true)
        val result = ShoppingVoiceMerge.merge(listOf(household, purchased), add(), "Me")
        assertEquals(0, result.id)
        assertEquals("Me", result.assignedTo)
        assertEquals(2, result.quantityNeeded)
        assertEquals(1, household.quantityNeeded)
    }

    @Test fun packageCountsKeepTheirLabelWhileMeasuredAmountsStaySeparate() {
        val milk = item("Milk", quantity = 1, label = "1 bottle")
        assertEquals("3 bottles", ShoppingVoiceMerge.merge(listOf(milk), add("milk"), "Me").quantityLabel)
        listOf("1 kg", "1 L", "500 g", "1 tbsp", "1 mystery", "200 g (fully covered)", "2 pcs").forEach { label ->
            assertEquals(label, 0, ShoppingVoiceMerge.merge(listOf(item(label = label)), add(), "Me").id)
        }
    }

    @Test fun repeatedItemsInTheSameCommandArePreviewedCumulatively() {
        val commands = listOf(add("egg", 1), add("eggs", 2))
        assertEquals(listOf(1, 3), ShoppingVoiceMerge.preview(emptyList(), commands, "Household").map { it.total })
        assertEquals(listOf(2, 4), ShoppingVoiceMerge.preview(listOf(item()), commands, "Me").map { it.total })
    }

    @Test fun voiceEditedAutoRowsKeepTheirInventoryLink() {
        val automatic = item().copy(sourceItemId = 12, addedBy = "Auto")
        val merged = ShoppingVoiceMerge.merge(listOf(automatic), add(), "Me")
        assertEquals(12, merged.sourceItemId)
        assertEquals(ShoppingVoiceMerge.VOICE_SOURCE, merged.addedBy)
        assertEquals(3.5, merged.estimatedUnitPrice, 0.0)
    }

    @Test fun invalidTargetsAndOverflowAreRejected() {
        listOf("", "Shared", "Someone").forEach { owner ->
            assertTrue(runCatching { ShoppingVoiceMerge.merge(emptyList(), add(), owner) }.isFailure)
        }
        assertTrue(runCatching { ShoppingVoiceMerge.merge(listOf(item(quantity = Int.MAX_VALUE)), add(), "Me") }.isFailure)
        assertTrue(runCatching { ShoppingVoiceMerge.merge(emptyList(), add(quantity = 0), "Me") }.isFailure)
    }
}

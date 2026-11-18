package com.xmum.smartgrocery.utils

import org.junit.Assert.*
import org.junit.Test

class IngredientTextParserTest {
    private fun parse(vararg lines: String) = IngredientTextParser.parse(lines.toList())

    @Test fun parsesSeveralGroceryRowsAndCategories() {
        val rows = parse("Eggs x12", "Milk 2 bottles", "2 packets brown rice")
        assertEquals(listOf("Eggs", "Milk", "brown rice"), rows.map { it.name })
        assertEquals(listOf(12, 2, 2), rows.map { it.quantity })
        assertEquals(listOf("Dairy", "Dairy", "Pantry"), rows.map { it.category })
        assertTrue(rows.all { it.selected && it.quantityDetected })
    }
    @Test fun labelWeightsAndNutritionNeverBecomeCounts() {
        val rows = parse("FRESH EGGS", "12 pcs", "NET WEIGHT 600 g", "Nutrition facts", "Protein 12 g", "Energy 600 kcal")
        assertEquals(1, rows.size)
        assertEquals("FRESH EGGS", rows.single().name)
        assertEquals(12, rows.single().quantity)
    }
    @Test fun volumeAndWeightLeaveUnknownPieceCountsForReview() {
        val rows = parse("Milk 1L", "Chicken 500g", "Brown rice 1 kg")
        assertEquals(listOf("Milk", "Chicken", "Brown rice"), rows.map { it.name })
        assertTrue(rows.all { it.quantity == 1 && !it.quantityDetected })
    }
    @Test fun receiptsStripPricesAndSkipTotalsDatesAndBarcodes() {
        val rows = parse("Milk RM5.50", "Qty: 2", "Bread 3.50", "Subtotal RM14.50", "2026-10-21", "9551234567890", "Thank you")
        assertEquals(listOf("Milk", "Bread"), rows.map { it.name })
        assertEquals(2, rows.first().quantity)
        assertEquals(1, rows.last().quantity)
    }
    @Test fun chineseAndMalayLabelsHaveLocalCategories() {
        val rows = parse("鸡蛋12个", "牛奶 2瓶", "盐", "Susu x2", "3 biji telur")
        assertEquals(listOf(12, 2, 1, 2, 3), rows.map { it.quantity })
        assertEquals(listOf("Dairy", "Dairy", "Pantry", "Dairy", "Dairy"), rows.map { it.category })
    }
    @Test fun fullWidthCountsAndMultipliedPacks() {
        val rows = parse("１２个鸡蛋", "2 x bottles of milk")
        assertEquals(listOf(12, 2), rows.map { it.quantity })
        assertEquals(listOf("鸡蛋", "milk"), rows.map { it.name })
    }
    @Test fun unknownTextStartsUncheckedAndStaysEditableAsCandidate() {
        val rows = parse("Unfamiliar brand", "Milk")
        assertEquals(2, rows.size)
        assertFalse(rows.first().selected)
        assertEquals("Other", rows.first().category)
        assertTrue(rows.last().selected)
    }
    @Test fun duplicateOcrLinesDoNotDoubleTheQuantity() {
        val rows = parse("Milk", "Milk x2", "Milk x2")
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().quantity)
    }
    @Test fun invalidCountsDoNotStartSelected() {
        listOf("0 eggs", "Milk x1000", "Milk x1.5", "Milk x-2").forEach {
            assertFalse(it, parse(it).single().selected)
        }
    }
    @Test fun emptyAndNonProductMetadataProduceNoItems() {
        assertTrue(parse("", "NET WEIGHT 1kg", "Ingredients: milk, sugar", "Best before 2026-12-31", "500g", "RM12.00").isEmpty())
    }
}

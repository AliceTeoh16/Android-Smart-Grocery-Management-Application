package com.xmum.smartgrocery.utils

import org.junit.Assert.*
import org.junit.Test

class InventoryUnitsTest {
    @Test fun dozenUsesIndividualPiecesWithoutRounding() {
        assertEquals(12, InventoryUnits.stockQuantity("1", "dozen", false))
        assertEquals(24, InventoryUnits.stockQuantity("2", "dozen", false))
        assertEquals(6, InventoryUnits.stockQuantity("0.5", "dozen", false))
        assertEquals("pcs", InventoryUnits.stockUnit("dozen"))
    }
    @Test fun metricAmountsUseExactWholeBaseUnits() {
        assertEquals(500, InventoryUnits.stockQuantity("0.5", "kg", false))
        assertEquals("g", InventoryUnits.stockUnit("kg"))
        assertEquals(1250, InventoryUnits.stockQuantity("1.25", "L", false))
        assertEquals("ml", InventoryUnits.stockUnit("L"))
        assertEquals(3, InventoryUnits.stockQuantity("3", "packs", false))
    }
    @Test fun invalidAmountsAreRejectedInsteadOfBecomingOne() {
        for ((quantity, unit) in listOf("" to "pcs", "-1" to "pcs", "NaN" to "pcs",
            "0" to "dozen", "0.1" to "dozen", "1.5" to "packs", "2000000001" to "pcs"))
            assertTrue("$quantity $unit", runCatching { InventoryUnits.stockQuantity(quantity, unit, false) }.isFailure)
        assertEquals(0, InventoryUnits.stockQuantity("0", "pcs", true))
    }
    @Test fun menuContainsExistingSystemUnitsAndExplicitStockPreview() {
        assertTrue(InventoryUnits.OPTIONS.containsAll(listOf("pcs", "kg", "g", "mg", "L", "ml",
            "packs", "bottles", "cartons", "cans", "bags", "boxes", "cups", "tbsp", "tsp", "cloves", "slices", "stalks", "dozen")))
        assertEquals("Stock will be saved as 12 pcs.", InventoryUnits.preview("1", "dozen"))
    }
}

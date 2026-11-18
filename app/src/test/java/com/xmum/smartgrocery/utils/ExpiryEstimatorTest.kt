package com.xmum.smartgrocery.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpiryEstimatorTest {

    @Test
    fun estimateDays_packagedMilk_usesOneWeek() {
        assertEquals(7, ExpiryEstimator.estimateDays("Milk", "Dairy"))
    }

    @Test
    fun estimateDays_freshProduce_usesItemSpecificRules() {
        assertEquals(7, ExpiryEstimator.estimateDays("Tomato", "Fruits & Vegetables"))
        assertEquals(21, ExpiryEstimator.estimateDays("Potato", "Fruits & Vegetables"))
    }

    @Test
    fun estimateDays_fishAndMeat_useShortFreshFoodRules() {
        assertEquals(2, ExpiryEstimator.estimateDays("Fish", "Meat & Seafood"))
        assertEquals(3, ExpiryEstimator.estimateDays("Chicken meat", "Meat & Seafood"))
    }

    @Test
    fun estimateDays_categoryFallback_worksWhenNameIsUnknown() {
        assertEquals(7, ExpiryEstimator.estimateDays("", "Fruits & Vegetables"))
        assertEquals(180, ExpiryEstimator.estimateDays("", "Pantry"))
    }
}

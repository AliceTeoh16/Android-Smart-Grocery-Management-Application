package com.xmum.smartgrocery.utils

import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import org.junit.Assert.*
import org.junit.Test

class ChecklistShoppingMergeTest {
    private fun row(name: String="Flour", label: String="200g", owner: String="Me", checked: Boolean=false) =
        ShoppingListItem(id=1,itemName=name,category="Pantry",quantityNeeded=200,quantityLabel=label,
            estimatedUnitPrice=0.05,assignedTo=owner,isChecked=checked,createdAt=10,updatedAt=10)
    private fun add(name: String="Flour", label: String="200g") = ChecklistShoppingMerge.Addition(name,"Pantry",label)
    @Test fun pantryQuantitiesAccumulateInsteadOfBeingDeduplicatedIntoOnePack() {
        val before=row(); val result=ChecklistShoppingMerge.merge(listOf(before),add(),"Me",20)
        assertEquals(1,result.id); assertEquals("400 g",result.quantityLabel); assertEquals(400,result.quantityNeeded)
        assertEquals(before.estimatedUnitPrice,result.estimatedUnitPrice,0.0); assertEquals(10L,result.createdAt)
        assertEquals("200g",before.quantityLabel)
    }
    @Test fun compatibleWeightAndVolumeUnitsAccumulateWithoutRoundingAwaySmallAmounts() {
        assertEquals("400 g",ChecklistShoppingMerge.merge(listOf(row()),add(label="0.2 kg"),"Me").quantityLabel)
        assertEquals("1500 ml",ChecklistShoppingMerge.merge(listOf(row("Milk","1 L")),add("Milk","500ml"),"Me").quantityLabel)
        assertEquals("0.75 tsp",ChecklistShoppingMerge.merge(listOf(row("Salt","½ tsp")),add("Salt","1/4 tsp"),"Me").quantityLabel)
        assertEquals("2.25 cups",ChecklistShoppingMerge.merge(listOf(row("Rice","1 1/2 cups")),add("Rice","¾ cup"),"Me").quantityLabel)
    }
    @Test fun chineseAndPluralAliasesMatchWithoutMatchingDifferentFoods() {
        assertEquals(1,ChecklistShoppingMerge.merge(listOf(row("Eggs (鸡蛋)","2 pcs")),add("鸡蛋","2个"),"Me").id)
        assertEquals("4 pcs",ChecklistShoppingMerge.merge(listOf(row("Eggs","2 pcs")),add("Egg","2 pcs"),"Me").quantityLabel)
        assertEquals(0,ChecklistShoppingMerge.merge(listOf(row("Eggplant","200g")),add("Egg","2 pcs"),"Me").id)
    }
    @Test fun pendingStatusIsGlobalAcrossOwnersAndIgnoresBoughtItems() {
        val pending=ChecklistShoppingMerge.pendingNames(listOf(row("Eggs (鸡蛋)","2 pcs","Household"),row("Milk","1 bottle",checked=true)))
        assertTrue(ChecklistShoppingMerge.isPending("Egg",pending)); assertTrue(ChecklistShoppingMerge.isPending("鸡蛋",pending))
        assertFalse(ChecklistShoppingMerge.isPending("Milk",pending)); assertFalse(ChecklistShoppingMerge.isPending("Eggplant",pending))
        assertFalse(ChecklistShoppingMerge.isPending("Egg",ChecklistShoppingMerge.pendingNames(emptyList())))
    }
    @Test fun additionNeverChangesAnotherOwnerOrAnAlreadyBoughtRow() {
        assertEquals(0,ChecklistShoppingMerge.merge(listOf(row(owner="Household")),add(),"Me").id)
        assertEquals(0,ChecklistShoppingMerge.merge(listOf(row(checked=true)),add(),"Me").id)
    }
    @Test fun incompatibleOrUnquantifiedMeasuresDoNotOverwriteExistingQuantities() {
        val before=row(label="1 pack")
        assertEquals(0,ChecklistShoppingMerge.merge(listOf(before),add(),"Me").id)
        assertEquals("1 pack",before.quantityLabel)
        assertEquals(0,ChecklistShoppingMerge.merge(listOf(row(label="to taste")),add(label="to taste"),"Me").id)
    }
    @Test fun packageAliasesAndUnlabelledCountsRemainAccumulative() {
        assertEquals("3 bottles",ChecklistShoppingMerge.merge(listOf(row("Milk","1 bottle")),add("Milk","2 botol"),"Me").quantityLabel)
        assertEquals("5 pcs",ChecklistShoppingMerge.merge(listOf(row("Eggs","").copy(quantityNeeded=3)),add("Eggs","2 pcs"),"Me").quantityLabel)
    }
    @Test fun mergingAutomaticRowsProtectsTheirConfirmedQuantityFromLowStockRefresh() {
        val result=ChecklistShoppingMerge.merge(listOf(row(label="2 pcs").copy(sourceItemId=4,addedBy="Auto")),add(label="1 pc"),"Me")
        assertEquals(4,result.sourceItemId); assertEquals(ChecklistShoppingMerge.SOURCE,result.addedBy)
        assertEquals("3 pcs",result.quantityLabel)
    }
    @Test fun overflowingQuantityIsRejectedBeforeAnyRowIsChanged() {
        assertTrue(runCatching { ChecklistShoppingMerge.merge(listOf(row(label="2147483647 pcs")),add(label="1 pc"),"Me") }.isFailure)
    }
}

package com.xmum.smartgrocery.ui.recipes

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xmum.smartgrocery.data.local.entity.ChecklistHistoryItem
import com.xmum.smartgrocery.data.repository.RecipeRepository
import java.util.UUID

/** Complete, versioned recipe state in Room; columns retain the existing status/shopping contracts. */
object ChecklistHistorySnapshot {
    private val gson = Gson()
    private data class Payload(val version: Int = 1, val checklist: SavedChecklist)

    fun newId(timestamp: Long): String = "meal_${timestamp}_${UUID.randomUUID()}"

    fun encode(checklist: SavedChecklist): ChecklistHistoryItem = ChecklistHistoryItem(
        checklist.id, checklist.dishName, checklist.servings, checklist.status, checklist.createdAt,
        gson.toJson(checklist.missingChecklist), gson.toJson(checklist.inventoryDeducted),
        gson.toJson(checklist.addedShoppingItemKeys.orEmpty()), gson.toJson(Payload(checklist = checklist)))

    fun decode(item: ChecklistHistoryItem, legacy: SavedChecklist? = null): SavedChecklist {
        val keys = strings(item.addedShoppingKeysJson)
        val missingType = object : TypeToken<List<RecipeRepository.MissingChecklistItem>>() {}.type
        val coveredType = object : TypeToken<List<RecipeRepository.DeductedInventoryItem>>() {}.type
        val missing = runCatching { gson.fromJson<List<RecipeRepository.MissingChecklistItem>>(item.missingChecklistJson, missingType) }.getOrNull().orEmpty()
        val covered = runCatching { gson.fromJson<List<RecipeRepository.DeductedInventoryItem>>(item.inventoryDeductedJson, coveredType) }.getOrNull().orEmpty()
        if (item.stateJson.isNotBlank()) {
            val stored = runCatching { gson.fromJson(item.stateJson, Payload::class.java) }.getOrNull()
            val saved = stored?.checklist
            if (stored?.version == 1 && saved != null && saved.id == item.id && saved.createdAt == item.createdAt &&
                saved.dishName == item.dishName && saved.servings == item.servings &&
                saved.missingChecklist == missing && saved.inventoryDeducted == covered) {
                return saved.copy(status = item.status, addedShoppingItemKeys = keys)
            }
        }
        val fallback = SavedChecklist(item.id, item.dishName, item.servings, item.status, item.createdAt, missing, covered, keys)
        // Preferences are used only to backfill a legacy row, never to override a Room snapshot.
        return if (item.stateJson.isBlank()) fallback.withCookingDetailsFrom(legacy) else fallback
    }

    private fun strings(json: String): List<String> = runCatching {
        gson.fromJson(json, Array<String>::class.java)?.toList()
    }.getOrNull().orEmpty()
}

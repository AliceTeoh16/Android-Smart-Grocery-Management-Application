package com.xmum.smartgrocery.data.local.entity

/** One small latest-change label; input-source metadata remains separate. */
object SharedItemChange {
    fun label(householdId: String?, creator: String?, modifier: String?, action: String?): String? {
        if (householdId == null) return null
        if (!modifier.isNullOrBlank() && action in setOf("Added", "Updated", "Checked", "Unchecked"))
            return "$action by $modifier"
        return creator?.takeIf { it.isNotBlank() }?.let { "Added by $it" }
    }
}

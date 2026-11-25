package com.xmum.smartgrocery.ui.inventory

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.databinding.ItemGroceryBinding
import com.xmum.smartgrocery.utils.DateUtils

class InventoryAdapter(
    private val onIncrement: (InventoryCardItem) -> Unit,
    private val onDecrement: (InventoryCardItem) -> Unit,
    private val onEdit: (GroceryItem) -> Unit,
    private val onItemLongClick: (InventoryCardItem) -> Unit,
    private val onShowBatches: (InventoryCardItem) -> Unit,
    private val onToss: (InventoryCardItem) -> Unit,
    private val onUpdateExpiry: (GroceryItem) -> Unit
) : ListAdapter<InventoryCardItem, InventoryAdapter.ViewHolder>(DiffCallback()) {

    private var expiryWarningDays: Int = 3

    inner class ViewHolder(private val binding: ItemGroceryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(card: InventoryCardItem) {
            val item = card.item
            binding.textItemName.text = item.name
            binding.textItemMeta.text = listOfNotNull(item.subcategory, "RM ${String.format("%.2f", item.price)}/${item.unit}",
                item.storageLocation.takeUnless { it == "Not set" }).joinToString(" · ")
            binding.textQuantity.text = card.totalQuantity.toString()
            binding.textQuantityUnit.text = "Qty (${item.unit})"
            binding.textCategoryPill.text = categoryLabelFor(item.category)
            val categoryStyle = categoryStyleFor(item)
            val accentColor = binding.root.context.getColor(accentColorFor(card))
            binding.viewCategoryIndicator.background = categoryIndicatorBackground(accentColor)
            binding.viewCategoryIndicator.elevation = 0f
            binding.textCategoryPill.setTextColor(categoryStyle.textColor)
            binding.textCategoryPill.background = categoryPillBackground(
                categoryStyle.backgroundColor,
                categoryStyle.accentColor
            )
            bindCardState(card)

            // Click listeners
            binding.buttonIncrement.setOnClickListener { onIncrement(card) }
            binding.buttonDecrement.setOnClickListener { onDecrement(card) }
            binding.buttonEditItem.setOnClickListener { onEdit(item) }
            binding.buttonToss.setOnClickListener { onToss(card) }
            binding.buttonUpdateExpiry.setOnClickListener { onUpdateExpiry(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(card)
                true
            }
            binding.root.setOnClickListener { onShowBatches(card) }
        }

        fun bindQuantityAndStatus(card: InventoryCardItem) {
            binding.textQuantity.text = card.totalQuantity.toString()
            binding.textQuantityUnit.text = "Qty (${card.item.unit})"
            bindCardState(card)
            binding.buttonIncrement.setOnClickListener { onIncrement(card) }
            binding.buttonDecrement.setOnClickListener { onDecrement(card) }
        }

        private fun bindCardState(card: InventoryCardItem) {
            binding.textSharedChange.text = card.changeIndicator.orEmpty()
            binding.textSharedChange.visibility = if (card.changeIndicator != null && card.item.householdId != null) View.VISIBLE else View.GONE
            val item = card.item
            val isExpired = DateUtils.isExpired(card.earliestExpiryDate)
            binding.textExpiry.visibility = View.VISIBLE
            binding.textExpiry.text = when {
                card.earliestExpiryDate == null -> "Expiry: Not Set"
                isExpired -> "Expired · ${DateUtils.formatDate(card.earliestExpiryDate)}"
                DateUtils.isExpiringWithin(card.earliestExpiryDate, expiryWarningDays) -> "Expires in ${DateUtils.daysUntil(card.earliestExpiryDate)} days"
                else -> "Exp ${DateUtils.formatDate(card.earliestExpiryDate)}"
            }
            binding.layoutExpiredActions.visibility = if (isExpired) View.VISIBLE else View.GONE
            binding.buttonEditItem.visibility = if (isExpired) View.GONE else View.VISIBLE
        }

        private fun accentColorFor(card: InventoryCardItem): Int {
            val item = card.item
            return when {
                DateUtils.isExpired(card.earliestExpiryDate) -> R.color.chart_coral
                DateUtils.isExpiringWithin(card.earliestExpiryDate, expiryWarningDays) -> R.color.premium_amber
                card.totalQuantity <= item.threshold && !item.categorySelection.isPantryCategory() -> R.color.premium_amber
                else -> R.color.chart_green
            }
        }
    }

    private fun String.isPantryCategory(): Boolean {
        val normalized = lowercase()
        return normalized.contains("pantry") ||
            normalized.contains("seasoning") ||
            normalized.contains("spice")
    }

    fun setExpiryWarningDays(days: Int) {
        if (expiryWarningDays != days) {
            expiryWarningDays = days
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATUS)
        }
    }

    private fun categoryStyleFor(item: GroceryItem): CategoryStyle {
        val category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(item.category)
        return CategoryStyle(category.background.toInt(), category.foreground.toInt(), category.foreground.toInt())
    }

    private fun categoryLabelFor(category: String): String =
        com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(category).label

    private fun categoryIndicatorBackground(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f
            setColor(accentColor)
        }
    }

    private fun categoryPillBackground(backgroundColor: Int, accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(backgroundColor)
            setStroke(1, accentColor)
        }
    }

    private data class CategoryStyle(
        val backgroundColor: Int,
        val textColor: Int,
        val accentColor: Int
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroceryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(getItem(position))
            return
        }

        val item = getItem(position)
        if (payloads.contains(PAYLOAD_QUANTITY) || payloads.contains(PAYLOAD_STATUS)) {
            holder.bindQuantityAndStatus(item)
        } else {
            holder.bind(item)
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<InventoryCardItem>() {
        override fun areItemsTheSame(oldItem: InventoryCardItem, newItem: InventoryCardItem): Boolean {
            return oldItem.item.name.equals(newItem.item.name, ignoreCase = true) &&
                oldItem.item.unit == newItem.item.unit && oldItem.item.storageLocation == newItem.item.storageLocation
        }

        override fun areContentsTheSame(oldItem: InventoryCardItem, newItem: InventoryCardItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: InventoryCardItem, newItem: InventoryCardItem): Any? {
            return if (
                oldItem.item.name.equals(newItem.item.name, ignoreCase = true) &&
                oldItem.totalQuantity != newItem.totalQuantity
            ) {
                PAYLOAD_QUANTITY
            } else {
                null
            }
        }
    }

    private companion object {
        const val PAYLOAD_QUANTITY = "payload_quantity"
        const val PAYLOAD_STATUS = "payload_status"
    }
}

package com.xmum.smartgrocery.ui.shopping

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.data.local.entity.ShoppingListItem
import com.xmum.smartgrocery.databinding.ItemShoppingListBinding

class ShoppingListAdapter(
    private val onCheckedChange: (ShoppingListItem, Boolean) -> Unit,
    private val onAssignClick: (ShoppingListItem, View) -> Unit,
    private val onDelete: (ShoppingListItem) -> Unit
) : ListAdapter<ShoppingListItem, ShoppingListAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemShoppingListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShoppingListItem) {
            binding.root.animate().cancel()
            binding.root.alpha = 1f
            binding.textItemName.text = item.itemName
            binding.textCategory.text = listOfNotNull(item.category, item.subcategory).joinToString(" · ")
            val category = com.xmum.smartgrocery.utils.GroceryCategories.mainCategory(item.category)
            binding.textCategory.setTextColor(category.foreground.toInt())
            binding.textCategory.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f
                setColor(category.background.toInt())
            }
            binding.textQuantityNeeded.text = "Buy ${item.displayQuantityLabel()}"
            binding.textEstimatedCost.text = "RM ${String.format("%.2f", item.quantityNeeded * item.estimatedUnitPrice)} est."
            bindInteractionState(item)
        }

        fun bindInteractionState(item: ShoppingListItem) {
            binding.root.animate().cancel()
            binding.root.alpha = 1f
            binding.checkboxPurchased.setOnCheckedChangeListener(null)
            binding.checkboxPurchased.isChecked = item.isChecked
            binding.textMember.text = "For ${item.assignedTo.displayAssignee()}"
            binding.textAddedBy.text = item.sharedChangeIndicator.orEmpty()
            binding.textAddedBy.visibility = if (item.sharedChangeIndicator != null) View.VISIBLE else View.GONE

            applyPurchasedVisual(item.isChecked)

            binding.checkboxPurchased.setOnCheckedChangeListener { _, checked ->
                handlePurchasedChange(item, checked)
            }
            binding.buttonAssign.setOnClickListener {
                onAssignClick(item, it)
            }
            binding.buttonDelete.setOnClickListener {
                onDelete(item)
            }
        }

        private fun handlePurchasedChange(item: ShoppingListItem, checked: Boolean) {
            if (checked && item.estimatedUnitPrice <= 0.0) {
                binding.checkboxPurchased.setOnCheckedChangeListener(null)
                binding.checkboxPurchased.isChecked = false
                binding.checkboxPurchased.setOnCheckedChangeListener { _, newChecked ->
                    handlePurchasedChange(item, newChecked)
                }
                applyPurchasedVisual(false)
                onCheckedChange(item, true)
                return
            }

            if (checked) {
                applyPurchasedVisual(true)
                binding.root.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction {
                        onCheckedChange(item, true)
                    }
                    .start()
            } else {
                applyPurchasedVisual(false)
                onCheckedChange(item, false)
            }
        }

        private fun applyPurchasedVisual(isPurchased: Boolean) {
            val context = binding.root.context
            val cardColor = if (isPurchased) R.color.mint_soft else R.color.surface
            binding.root.setCardBackgroundColor(ContextCompat.getColor(context, cardColor))
            binding.textItemName.paintFlags = if (isPurchased) {
                binding.textItemName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.textItemName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            binding.textItemName.alpha = if (isPurchased) 0.65f else 1f
            binding.textQuantityNeeded.alpha = if (isPurchased) 0.65f else 1f
            binding.textEstimatedCost.alpha = if (isPurchased) 0.65f else 1f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShoppingListBinding.inflate(
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
        if (payloads.contains(PAYLOAD_INTERACTION_STATE)) {
            holder.bindInteractionState(getItem(position))
        } else {
            holder.bind(getItem(position))
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ShoppingListItem>() {
        override fun areItemsTheSame(oldItem: ShoppingListItem, newItem: ShoppingListItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ShoppingListItem, newItem: ShoppingListItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: ShoppingListItem, newItem: ShoppingListItem): Any? {
            return if (
                oldItem.copy(
                    addedBy = newItem.addedBy,
                    assignedTo = newItem.assignedTo,
                    isChecked = newItem.isChecked,
                    updatedAt = newItem.updatedAt
                ) == newItem
            ) {
                PAYLOAD_INTERACTION_STATE
            } else {
                null
            }
        }
    }

    private fun String.displayAssignee(): String {
        return if (equals("Housemate", ignoreCase = true) || equals("Shared", ignoreCase = true)) {
            "Household"
        } else {
            this
        }
    }

    private fun ShoppingListItem.displayQuantityLabel(): String {
        val label = quantityLabel.ifBlank { quantityNeeded.toString() }
        if (!isRetailPantryItem()) return label
        if (label.isRetailUnitLabel()) return label
        return if (itemName.isLiquidLikePantryName() || label.isLiquidLikeUnit()) {
            "1 bottle"
        } else {
            "1 pack"
        }
    }

    private fun ShoppingListItem.isRetailPantryItem(): Boolean {
        return com.xmum.smartgrocery.utils.GroceryCategories.isFood(category) && (categorySelection.isRetailPantryText() || itemName.isRetailPantryText())
    }

    private fun String.isRetailPantryText(): Boolean {
        val normalized = lowercase()
        return listOf(
            "pantry",
            "seasoning",
            "seasonings",
            "spice",
            "spices",
            "salt",
            "sugar",
            "pepper",
            "soy sauce",
            "sauce",
            "oil",
            "vinegar",
            "flour",
            "starch",
            "cornstarch",
            "powder"
        ).any { normalized.contains(it) }
    }

    private fun String.isLiquidLikePantryName(): Boolean {
        val normalized = lowercase()
        return listOf("sauce", "oil", "vinegar", "syrup", "paste", "extract").any { normalized.contains(it) }
    }

    private fun String.isLiquidLikeUnit(): Boolean {
        val normalized = lowercase()
        return listOf("ml", "liter", "litre", "tbsp", "tablespoon", "tsp", "teaspoon").any {
            normalized.contains(it)
        }
    }

    private fun String.isRetailUnitLabel(): Boolean {
        val normalized = lowercase()
        return normalized.contains("pack") || normalized.contains("bottle")
    }

    private companion object {
        const val PAYLOAD_INTERACTION_STATE = "payload_interaction_state"
    }
}

package com.xmum.smartgrocery.ui.recipes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xmum.smartgrocery.data.repository.RecipeRepository
import com.xmum.smartgrocery.databinding.ItemMissingChecklistBinding

class RecipeAdapter(
    private val onSelectionChanged: (RecipeRepository.MissingChecklistItem, Boolean) -> Unit
) : ListAdapter<SelectableChecklistItem, RecipeAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemMissingChecklistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(selectableItem: SelectableChecklistItem) {
            val item = selectableItem.checklistItem
            binding.checkboxChecklistItem.setOnCheckedChangeListener(null)
            binding.checkboxChecklistItem.isChecked = selectableItem.isSelected
            binding.checkboxChecklistItem.isEnabled = selectableItem.isEditable
            binding.checkboxChecklistItem.contentDescription = "Select ${item.item}"
            binding.textChecklistItemName.text = item.item
            binding.textChecklistQuantity.text = item.neededQuantity
            binding.textChecklistCategory.text = item.category
            applyAddedVisual(selectableItem.isAddedToShopping)
            binding.checkboxChecklistItem.setOnCheckedChangeListener { _, checked ->
                onSelectionChanged(item, checked)
            }
            binding.root.setOnClickListener {
                if (selectableItem.isEditable) {
                    binding.checkboxChecklistItem.isChecked = !binding.checkboxChecklistItem.isChecked
                }
            }
        }

        private fun applyAddedVisual(isAdded: Boolean) {
            binding.textChecklistAddedStatus.visibility = if (isAdded) View.VISIBLE else View.GONE
            binding.textChecklistAddedStatus.contentDescription = if (isAdded) "Added to shopping list" else null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMissingChecklistBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private class DiffCallback : DiffUtil.ItemCallback<SelectableChecklistItem>() {
        override fun areItemsTheSame(
            oldItem: SelectableChecklistItem,
            newItem: SelectableChecklistItem
        ): Boolean {
            return oldItem.checklistItem.item == newItem.checklistItem.item &&
                oldItem.checklistItem.neededQuantity == newItem.checklistItem.neededQuantity &&
                oldItem.checklistItem.category == newItem.checklistItem.category
        }

        override fun areContentsTheSame(
            oldItem: SelectableChecklistItem,
            newItem: SelectableChecklistItem
        ): Boolean {
            return oldItem == newItem
        }
    }

}

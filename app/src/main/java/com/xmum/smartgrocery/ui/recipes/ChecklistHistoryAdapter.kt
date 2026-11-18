package com.xmum.smartgrocery.ui.recipes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.databinding.ItemChecklistHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChecklistHistoryAdapter(
    private val onOpenChecklist: (SavedChecklist) -> Unit
) : ListAdapter<SavedChecklist, ChecklistHistoryAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss.SSS", Locale.getDefault())

    inner class ViewHolder(private val binding: ItemChecklistHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SavedChecklist) {
            val context = binding.root.context

            // 1. Dish title and servings
            binding.textHistoryDishTitle.text = "${item.dishName} - ${item.servings} serving(s)"

            // 2. Created timestamp / date
            binding.textHistoryCreatedAt.text = dateFormat.format(Date(item.createdAt))

            // 3. Status Badge
            val isCooked = item.status.equals("cooked", ignoreCase = true)
            val isCancelled = item.status.equals("cancelled", ignoreCase = true)

            when {
                isCooked -> {
                    binding.textHistoryStatusBadge.text = "Cooked"
                    binding.textHistoryStatusBadge.setBackgroundResource(R.drawable.bg_badge_cooked)
                    binding.textHistoryStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
                }
                isCancelled -> {
                    binding.textHistoryStatusBadge.text = "Cancelled"
                    binding.textHistoryStatusBadge.setBackgroundResource(R.drawable.bg_badge_cancelled)
                    binding.textHistoryStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                else -> {
                    binding.textHistoryStatusBadge.text = "Pending"
                    binding.textHistoryStatusBadge.setBackgroundResource(R.drawable.bg_badge_pending)
                    binding.textHistoryStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.harvest_gold))
                }
            }

            binding.imageHistoryExpandArrow.rotation = -90f
            binding.layoutHistoryExpanded.visibility = View.GONE
            binding.root.setOnClickListener { onOpenChecklist(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChecklistHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private class DiffCallback : DiffUtil.ItemCallback<SavedChecklist>() {
        override fun areItemsTheSame(oldItem: SavedChecklist, newItem: SavedChecklist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SavedChecklist, newItem: SavedChecklist): Boolean {
            return oldItem == newItem
        }
    }

}

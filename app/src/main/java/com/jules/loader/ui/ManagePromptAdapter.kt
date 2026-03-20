package com.jules.loader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.jules.loader.R
import java.util.Collections

class ManagePromptDiffCallback : DiffUtil.ItemCallback<PromptItem>() {
    override fun areItemsTheSame(oldItem: PromptItem, newItem: PromptItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PromptItem, newItem: PromptItem): Boolean {
        return oldItem == newItem
    }
}

class ManagePromptAdapter(
    private val disabledIds: Set<String>,
    private val onCheckedChange: (PromptItem, Boolean) -> Unit,
    private val onEditClick: (PromptItem) -> Unit,
    private val onDeleteClick: (PromptItem) -> Unit,
    private val onOrderChanged: (List<PromptItem>) -> Unit
) : ListAdapter<PromptItem, ManagePromptAdapter.ViewHolder>(ManagePromptDiffCallback()) {

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val currentListMutable = currentList.toMutableList()
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(currentListMutable, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(currentListMutable, i, i - 1)
            }
        }
        submitList(currentListMutable)
        onOrderChanged(currentListMutable)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_manage_prompt, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvPromptTitle)
        private val switchPrompt: MaterialSwitch = itemView.findViewById(R.id.switchPrompt)
        private val btnEdit: ImageView = itemView.findViewById(R.id.btnEditPrompt)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeletePrompt)

        fun bind(item: PromptItem) {
            tvTitle.text = item.title

            switchPrompt.setOnCheckedChangeListener(null)
            switchPrompt.isChecked = !disabledIds.contains(item.id)

            switchPrompt.setOnCheckedChangeListener { _, isChecked ->
                onCheckedChange(item, isChecked)
            }

            if (item.isCustom) {
                btnEdit.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE

                btnEdit.setOnClickListener { onEditClick(item) }
                btnDelete.setOnClickListener { onDeleteClick(item) }
            } else {
                btnEdit.visibility = View.GONE
                btnDelete.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onEditClick(item)
            }
        }
    }
}

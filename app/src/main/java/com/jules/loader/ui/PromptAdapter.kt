package com.jules.loader.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jules.loader.R
import java.util.Collections

class PromptAdapter(
    private val onItemClick: (PromptItem) -> Unit,
    private val onCustomAddClick: () -> Unit,
    private val onItemsReordered: (List<PromptItem>) -> Unit,
    private val onItemDisabled: (PromptItem) -> Unit
) : RecyclerView.Adapter<PromptAdapter.PromptViewHolder>() {

    private val items = mutableListOf<PromptItem>()
    var isEditMode = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newItems: List<PromptItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<PromptItem> = items

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onItemsReordered(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PromptViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_chip, parent, false)
        return PromptViewHolder(view)
    }

    override fun onBindViewHolder(holder: PromptViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PromptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnPrompt: MaterialButton = itemView.findViewById(R.id.btnPrompt)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnRemove)
        private var animator: ObjectAnimator? = null

        fun bind(item: PromptItem) {
            btnPrompt.text = item.title

            if (item.id == "custom_add") {
                // Style the "➕ Custom" button differently
                btnPrompt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                btnPrompt.setStrokeColorResource(com.google.android.material.R.color.material_dynamic_primary50)
                btnPrompt.strokeWidth = 2
                btnPrompt.setOnClickListener {
                    if (!isEditMode) onCustomAddClick()
                }
                btnRemove.visibility = View.GONE
                stopWiggle()
            } else {
                btnPrompt.setStrokeColorResource(android.R.color.transparent)
                btnPrompt.strokeWidth = 0
                btnPrompt.setOnClickListener {
                    if (!isEditMode) onItemClick(item)
                }

                btnPrompt.setOnLongClickListener {
                    if (!isEditMode) {
                        isEditMode = true
                    }
                    true
                }

                if (isEditMode) {
                    btnRemove.visibility = View.VISIBLE
                    startWiggle()
                } else {
                    btnRemove.visibility = View.GONE
                    stopWiggle()
                }

                btnRemove.setOnClickListener {
                    onItemDisabled(item)
                }
            }
        }

        private fun startWiggle() {
            if (animator == null) {
                val pvhRotation = PropertyValuesHolder.ofFloat(View.ROTATION, -2f, 2f, -2f)
                animator = ObjectAnimator.ofPropertyValuesHolder(itemView, pvhRotation).apply {
                    duration = 300
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
            }
            if (animator?.isStarted == false) {
                animator?.start()
            }
        }

        private fun stopWiggle() {
            animator?.cancel()
            itemView.rotation = 0f
        }
    }
}

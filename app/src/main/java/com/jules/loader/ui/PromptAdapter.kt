package com.jules.loader.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.flexbox.FlexboxLayoutManager
import com.jules.loader.R
import java.util.Collections

class PromptDiffCallback : DiffUtil.ItemCallback<PromptItem>() {
    override fun areItemsTheSame(oldItem: PromptItem, newItem: PromptItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PromptItem, newItem: PromptItem): Boolean {
        return oldItem == newItem
    }
}

open class PromptAdapter(
    private val onItemClick: (PromptItem) -> Unit,
    private val onCustomAddClick: () -> Unit,
    private val onItemDisabled: (PromptItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<PromptItem, PromptAdapter.PromptViewHolder>(PromptDiffCallback()) {


    // We maintain a list of active view holders to manually animate/update them
    // without triggering a full notifyDataSetChanged() that would cancel an active drag.
    private val activeHolders = mutableSetOf<PromptViewHolder>()

    var isEditMode = false
        set(value) {
            if (field != value) {
                field = value
                for (holder in activeHolders) {
                    holder.updateEditModeUI()
                }
                onEditModeChanged(value)
            }
        }

    protected open fun onEditModeChanged(editMode: Boolean) {}

    fun getItems(): List<PromptItem> = currentList

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
        // Instead of triggering a full diff visually for a drag (which resets the view holder animations),
        // we can just notify item moved. Since we extend ListAdapter, we need to pass the new list
        // but avoid DiffUtil tearing down the views. submitList handles this well enough, but to maintain the dragging
        // state seamlessly, notifyItemMoved is better. But with ListAdapter, we must submit the new list.
        submitList(currentListMutable)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PromptViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_chip, parent, false)
        val lp = view.layoutParams
        if (lp is FlexboxLayoutManager.LayoutParams) {
            lp.flexGrow = 1f
            lp.flexBasisPercent = 0.3f
        }
        return PromptViewHolder(view)
    }

    override fun onBindViewHolder(holder: PromptViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: PromptViewHolder) {
        super.onViewAttachedToWindow(holder)
        activeHolders.add(holder)
        holder.updateEditModeUI()
    }

    override fun onViewDetachedFromWindow(holder: PromptViewHolder) {
        super.onViewDetachedFromWindow(holder)
        activeHolders.remove(holder)
        holder.stopWiggle()
    }

    inner class PromptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnPrompt: MaterialButton = itemView.findViewById(R.id.btnPrompt)
        private val btnRemove: ImageView = itemView.findViewById(R.id.btnRemove)
        private var animator: ObjectAnimator? = null

        private var boundItemId: String? = null

        fun bind(item: PromptItem) {
            boundItemId = item.id
            btnPrompt.text = item.title

            if (item.id == "custom_add") {
                // Style the "➕ Custom" button differently
                btnPrompt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                btnPrompt.setStrokeColorResource(com.google.android.material.R.color.material_dynamic_primary50)
                btnPrompt.strokeWidth = 2
                btnPrompt.setOnClickListener {
                    if (!isEditMode) onCustomAddClick()
                }
                btnPrompt.setOnLongClickListener(null)
                btnRemove.setOnClickListener(null)

                // Ensure edit mode UI is correct initially
                updateEditModeUI()
            } else {
                btnPrompt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                btnPrompt.setStrokeColorResource(android.R.color.transparent)
                btnPrompt.strokeWidth = 0
                btnPrompt.setOnClickListener {
                    if (!isEditMode) onItemClick(item)
                }

                // Make it draggable immediately regardless of current mode
                btnPrompt.setOnLongClickListener {
                    if (!isEditMode) {
                        isEditMode = true
                    }
                    onStartDrag(this@PromptViewHolder)
                    true
                }

                btnRemove.setOnClickListener {
                    onItemDisabled(item)
                }

                // Ensure edit mode UI is correct initially
                updateEditModeUI()
            }
        }

        fun updateEditModeUI() {
            if (boundItemId == "custom_add") {
                btnRemove.visibility = View.GONE
                stopWiggle()
            } else {
                if (isEditMode) {
                    btnRemove.visibility = View.VISIBLE
                    startWiggle()
                } else {
                    btnRemove.visibility = View.GONE
                    stopWiggle()
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

        fun stopWiggle() {
            animator?.cancel()
            itemView.rotation = 0f
        }
    }
}

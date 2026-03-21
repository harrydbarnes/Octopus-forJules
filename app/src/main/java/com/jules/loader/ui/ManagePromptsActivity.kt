package com.jules.loader.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jules.loader.R
import com.jules.loader.databinding.ActivityManagePromptsBinding
import com.jules.loader.util.PreferenceUtils

class ManagePromptsActivity : BaseActivity() {

    private lateinit var binding: ActivityManagePromptsBinding
    private lateinit var adapter: ManagePromptAdapter
    private val gson = Gson()
    private val allPrompts = mutableListOf<PromptItem>()
    private val disabledPrompts = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagePromptsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.toolbar.inflateMenu(R.menu.menu_manage_prompts)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reset_all -> {
                    showGlobalResetConfirmation()
                    true
                }
                else -> false
            }
        }

        adapter = ManagePromptAdapter(
            disabledIds = disabledPrompts,
            onCheckedChange = { item, isChecked ->
                if (isChecked) {
                    disabledPrompts.remove(item.id)
                } else {
                    disabledPrompts.add(item.id)
                }
                saveDisabledPrompts()
            },
            onEditClick = { item -> showEditDialog(item) },
            onDeleteClick = { item -> deleteCustomPrompt(item) },
            onOrderChanged = { newOrder -> savePromptOrder(newOrder) }
        )

        loadData()
        updateMenuVisibility()

        binding.rvManagePrompts.layoutManager = LinearLayoutManager(this)
        binding.rvManagePrompts.adapter = adapter
        binding.rvManagePrompts.addItemDecoration(PromptDividerItemDecoration(this))

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.rvManagePrompts)

        binding.fabAddPrompt.setOnClickListener {
            showAddDialog()
        }

        binding.rvManagePrompts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && binding.fabAddPrompt.isExtended) {
                    binding.fabAddPrompt.shrink()
                } else if (dy < 0 && !binding.fabAddPrompt.isExtended) {
                    binding.fabAddPrompt.extend()
                }
            }
        })
    }

    private fun loadData() {
        val defaultPrompts = listOf(
            PromptItem("performance", getString(R.string.prompt_performance_title), false, "performance.md", true, "performance.md"),
            PromptItem("design", getString(R.string.prompt_design_title), false, "design.md", true, "design.md"),
            PromptItem("security", getString(R.string.prompt_security_title), false, "security.md", true, "security.md"),
            PromptItem("bug_hunt", getString(R.string.prompt_bug_hunt_title), false, "bug_hunt.md", true, "bug_hunt.md"),
            PromptItem("dependencies", getString(R.string.prompt_update_dependencies_title), false, "update_dependencies.md", true, "update_dependencies.md"),
            PromptItem("readme", getString(R.string.prompt_readme_title), false, "readme.md", true, "readme.md"),
            PromptItem("simplify", getString(R.string.prompt_simplify_title), false, "simplify.md", true, "simplify.md"),
            PromptItem("refactor", getString(R.string.prompt_refactor_title), false, "refactor.md", true, "refactor.md"),
            PromptItem("unit_tests", getString(R.string.prompt_unit_tests_title), false, "unit_tests.md", true, "unit_tests.md"),
            PromptItem("janitor", getString(R.string.prompt_janitor_title), false, "janitor.md", true, "janitor.md"),
            PromptItem("accessibility", getString(R.string.prompt_accessibility_title), false, "accessibility.md", true, "accessibility.md")
        )

        val customPromptsJson = PreferenceUtils.getCustomPromptsJson(this)
        val customPrompts: List<PromptItem> = if (!customPromptsJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<PromptItem>>() {}.type
            gson.fromJson(customPromptsJson, type)
        } else {
            emptyList()
        }

        val disabledPromptsJson = PreferenceUtils.getDisabledPromptsJson(this)
        if (!disabledPromptsJson.isNullOrEmpty()) {
            val type = object : TypeToken<Set<String>>() {}.type
            disabledPrompts.addAll(gson.fromJson(disabledPromptsJson, type))
        }

        val mergedPrompts = defaultPrompts.map { defaultItem ->
            customPrompts.find { it.id == defaultItem.id } ?: defaultItem
        }.toMutableList()

        // Add true custom prompts
        mergedPrompts.addAll(customPrompts.filter { it.isCustom && mergedPrompts.none { mp -> mp.id == it.id } })

        val combined = mergedPrompts.toMutableList()

        val promptOrderJson = PreferenceUtils.getPromptOrderJson(this)
        if (!promptOrderJson.isNullOrEmpty()) {
            val type = object : TypeToken<List<String>>() {}.type
            val savedOrder: List<String> = gson.fromJson(promptOrderJson, type)

            val orderedPrompts = mutableListOf<PromptItem>()
            for (id in savedOrder) {
                val item = combined.find { it.id == id }
                if (item != null) {
                    orderedPrompts.add(item)
                    combined.remove(item)
                }
            }
            orderedPrompts.addAll(combined)
            allPrompts.clear()
            allPrompts.addAll(orderedPrompts)
        } else {
            allPrompts.clear()
            allPrompts.addAll(combined)
        }

        adapter.submitList(allPrompts.toList())
    }

    private fun updateMenuVisibility() {
        val hasCustomPrompts = !PreferenceUtils.getCustomPromptsJson(this).isNullOrEmpty()
        val hasCustomOrder = !PreferenceUtils.getPromptOrderJson(this).isNullOrEmpty()
        val hasDisabledPrompts = !PreferenceUtils.getDisabledPromptsJson(this).isNullOrEmpty()

        val isModified = hasCustomPrompts || hasCustomOrder || hasDisabledPrompts
        binding.toolbar.menu.findItem(R.id.action_reset_all)?.isVisible = isModified
    }

    private fun showGlobalResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_reset_all_prompts_title)
            .setMessage(R.string.dialog_reset_all_prompts_message)
            .setPositiveButton(R.string.menu_reset_all) { _, _ ->
                PreferenceUtils.setCustomPromptsJson(this, "")
                PreferenceUtils.setPromptOrderJson(this, "")
                PreferenceUtils.setDisabledPromptsJson(this, "")

                allPrompts.clear()
                disabledPrompts.clear()
                loadData()
                updateMenuVisibility()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun saveDisabledPrompts() {
        PreferenceUtils.setDisabledPromptsJson(this, gson.toJson(disabledPrompts))
        updateMenuVisibility()
    }

    private fun savePromptOrder(items: List<PromptItem>) {
        val order = items.map { it.id }
        PreferenceUtils.setPromptOrderJson(this, gson.toJson(order))
        updateMenuVisibility()
    }

    private fun deleteCustomPrompt(item: PromptItem) {
        val customPromptsJson = PreferenceUtils.getCustomPromptsJson(this)
        if (!customPromptsJson.isNullOrEmpty()) {
            val type = object : TypeToken<MutableList<PromptItem>>() {}.type
            val customPrompts: MutableList<PromptItem> = gson.fromJson(customPromptsJson, type)
            customPrompts.removeAll { it.id == item.id }
            PreferenceUtils.setCustomPromptsJson(this, gson.toJson(customPrompts))
        }

        val pos = allPrompts.indexOf(item)
        if (pos != -1) {
            allPrompts.removeAt(pos)
            adapter.submitList(allPrompts.toList())
            savePromptOrder(allPrompts)
        }
    }

    private fun showAddDialog() {
        showDialog(null)
    }

    private fun showEditDialog(item: PromptItem) {
        showDialog(item)
    }

    private fun showDialog(itemToEdit: PromptItem?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_custom_prompt, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val tvDialogTitle = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogTitle)
        val etTitle = dialogView.findViewById<EditText>(R.id.etPromptTitle)
        val etEmoji = dialogView.findViewById<EditText>(R.id.etPromptEmoji)
        val etBody = dialogView.findViewById<EditText>(R.id.etPromptBody)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSavePrompt)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPrompt)
        val btnReset = dialogView.findViewById<Button>(R.id.btnResetPrompt)

        if (itemToEdit != null) {
            tvDialogTitle.text = getString(R.string.dialog_edit_custom_prompt_title)
            // Very naive split for emoji and title
            val parts = itemToEdit.title.split(" ", limit = 2)
            if (parts.size == 2 && isEmoji(parts[0])) {
                etEmoji.setText(parts[0])
                etTitle.setText(parts[1])
            } else {
                etTitle.setText(itemToEdit.title)
            }

            if (itemToEdit.isCustom) {
                etBody.setText(itemToEdit.body)
            } else {
                // For default prompts, body might be a filename or customized body.
                // We'll just show the current body if it doesn't end in .md, or load it from assets if it does.
                if (itemToEdit.body.endsWith(".md")) {
                    val defaultBody = try {
                        assets.open("prompts/${itemToEdit.body}").bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        android.util.Log.e("ManagePrompts", "Error reading asset ${itemToEdit.body}", e)
                        ""
                    }
                    etBody.setText(defaultBody)
                } else {
                    etBody.setText(itemToEdit.body)
                }

                btnReset.visibility = View.VISIBLE
                btnReset.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_reset_prompt_title)
                        .setMessage(R.string.dialog_reset_prompt_message)
                        .setPositiveButton(R.string.action_reset_prompt) { _, _ ->
                            val customPromptsJson = PreferenceUtils.getCustomPromptsJson(this)
                            val type = object : TypeToken<MutableList<PromptItem>>() {}.type
                            val customPrompts: MutableList<PromptItem> = if (!customPromptsJson.isNullOrEmpty()) {
                                gson.fromJson(customPromptsJson, type)
                            } else {
                                mutableListOf()
                            }

                            val idx = customPrompts.indexOfFirst { it.id == itemToEdit.id }
                            if (idx != -1) {
                                customPrompts.removeAt(idx)
                                PreferenceUtils.setCustomPromptsJson(this, gson.toJson(customPrompts))
                            }

                            try {
                                val originalFileName = itemToEdit.originalBody ?: "${itemToEdit.id}.md"

                                val allIdx = allPrompts.indexOfFirst { it.id == itemToEdit.id }
                                if (allIdx != -1) {
                                    allPrompts[allIdx] = allPrompts[allIdx].copy(title = itemToEdit.title, body = originalFileName)
                                    adapter.notifyItemChanged(allIdx)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ManagePrompts", "Error resetting prompt ${itemToEdit.id}", e)
                            }

                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val emoji = etEmoji.text.toString().trim()
            val body = etBody.text.toString().trim()

            if (title.isEmpty() || body.isEmpty()) {
                Toast.makeText(this, R.string.toast_prompt_fields_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val finalTitle = if (emoji.isNotEmpty()) "$emoji $title" else title

            val customPromptsJson = PreferenceUtils.getCustomPromptsJson(this)
            val type = object : TypeToken<MutableList<PromptItem>>() {}.type
            val customPrompts: MutableList<PromptItem> = if (!customPromptsJson.isNullOrEmpty()) {
                gson.fromJson(customPromptsJson, type)
            } else {
                mutableListOf()
            }

            if (itemToEdit != null) {
                // Update
                val updatedItem = itemToEdit.copy(title = finalTitle, body = body)

                // If it's a default prompt being edited, it becomes an override saved in customPrompts
                // with the same ID, so it overrides the default on load.
                val idx = customPrompts.indexOfFirst { it.id == itemToEdit.id }
                if (idx != -1) {
                    customPrompts[idx] = updatedItem
                } else if (!itemToEdit.isCustom) {
                    customPrompts.add(updatedItem)
                }

                val allIdx = allPrompts.indexOfFirst { it.id == itemToEdit.id }
                if (allIdx != -1) {
                    allPrompts[allIdx] = updatedItem
                    adapter.submitList(allPrompts.toList())
                }
            } else {
                // Add
                val newItem = PromptItem(
                    id = "custom_${java.util.UUID.randomUUID()}",
                    title = finalTitle,
                    isCustom = true,
                    body = body
                )
                customPrompts.add(newItem)
                allPrompts.add(newItem)
                adapter.submitList(allPrompts.toList())
                savePromptOrder(allPrompts)
            }

            PreferenceUtils.setCustomPromptsJson(this, gson.toJson(customPrompts))
            updateMenuVisibility()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun isEmoji(str: String): Boolean {
        if (str.isEmpty()) return false
        val cp = str.codePointAt(0)
        return Character.isSurrogate(str[0]) || cp > 0x2500
    }
}

package com.jules.loader.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.internal.CheckableImageButton
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.jules.loader.R
import com.jules.loader.data.JulesRepository
import com.jules.loader.data.model.SourceContext
import com.jules.loader.databinding.ActivityCreateTaskBinding
import com.jules.loader.util.PreferenceUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.activity.OnBackPressedCallback
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.JustifyContent
import com.google.android.flexbox.FlexWrap
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.EditText
import android.widget.Button

class CreateTaskActivity : BaseActivity() {

    private lateinit var binding: ActivityCreateTaskBinding
    private lateinit var viewModel: CreateTaskViewModel
    private lateinit var promptAdapter: PromptAdapter
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent
    private var isListening = false
    private var originalTextBeforeSpeech = ""
    private var repoAdapter: ArrayAdapter<String>? = null

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::promptAdapter.isInitialized && promptAdapter.isEditMode) {
                promptAdapter.isEditMode = false
                savePromptOrder(promptAdapter.getItems())
            }
        }
    }
    private var branchAdapter: ArrayAdapter<String>? = null
    private val sourceMap = mutableMapOf<String, String>()
    private var isTaskInputExpanded = false

    companion object {
        private val TAG = CreateTaskActivity::class.java.simpleName
        private const val PERMISSION_REQUEST_AUDIO = 100
        private const val MIN_DB_LEVEL = -2f
        private const val MAX_DB_LEVEL = 10f
        private const val DB_LEVEL_RANGE = MAX_DB_LEVEL - MIN_DB_LEVEL
        private const val ANIMATION_DURATION_MS = 200L
        // Interval for the "Listening." → ".." → "..." ellipsis animation
        private const val ELLIPSIS_INTERVAL_MS = 500L
        // Delay after results arrive before the wave settles to flat and the sheet dismisses.
        // Gives the user time to read their transcription and naturally pause between phrases.
        private const val SETTLE_DISMISS_DELAY_MS = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set up shared element transition
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())

        window.sharedElementEnterTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 300L
        }
        window.sharedElementReturnTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 250L
        }

        super.onCreate(savedInstanceState)
        binding = ActivityCreateTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.transitionName = "shared_element_container"

        val repository = JulesRepository.getInstance(applicationContext)
        val factory = CreateTaskViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[CreateTaskViewModel::class.java]

        binding.toolbar.setNavigationOnClickListener {
            if (::promptAdapter.isInitialized && promptAdapter.isEditMode) {
                promptAdapter.isEditMode = false
                savePromptOrder(promptAdapter.getItems())
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        setupRepoSelector()
        setupVoiceInput()
        setupPromptGallery()
        setupTaskInputExpansion()
        setupKeyboardFocusClear()
        observeViewModel()

        binding.btnStartTask.setOnClickListener {
            val prompt = binding.taskInput.text.toString().trim()
            if (prompt.isNotEmpty()) {
                val repoInputText = binding.repoInput.text.toString().takeIf { it.isNotBlank() }
                val repo = if (repoInputText != null) {
                    sourceMap[repoInputText]
                } else null
                val branch = binding.branchInput.text.toString().takeIf { it.isNotBlank() }
                val automationMode = if (binding.switchAutoCreatePr.isChecked) "AUTO_CREATE_PR" else null
                val requirePlanApproval = binding.switchRequirePlanApproval.isChecked
                viewModel.submitTask(prompt, repo, branch, automationMode, requirePlanApproval)
            } else {
                binding.taskInput.error = "Please enter a task"
            }
        }

        // Handle Share Intent
        if (intent?.action == android.content.Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            if (sharedText != null) {
                binding.taskInput.setText(sharedText)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showVoiceDialog()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.availableSources.collectLatest { sources ->
                        sourceMap.clear()

                        val isShortenEnabled = PreferenceUtils.isShortenRepoNamesEnabled(this@CreateTaskActivity)
                        val repoNameCounts = sources.groupingBy { it.cleanSource.substringAfterLast('/') }.eachCount()

                        val sourceNames = sources.map { source ->
                            val shortName = source.cleanSource.substringAfterLast('/')
                            val isDuplicate = (repoNameCounts[shortName] ?: 0) > 1

                            val displayName = if (isShortenEnabled && !isDuplicate) {
                                shortName
                            } else {
                                source.cleanSource
                            }

                            sourceMap[displayName] = source.source
                            displayName
                        }.distinct()

                        val wasPopupShowing = binding.repoInput.isPopupShowing
                        repoAdapter?.clear()
                        repoAdapter?.addAll(sourceNames)
                        if (wasPopupShowing) {
                            binding.repoInput.post { binding.repoInput.showDropDown() }
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collectLatest { isLoading ->
                        binding.btnStartTask.isEnabled = !isLoading
                        binding.btnStartTask.text = if (isLoading) getString(R.string.create_task_starting) else getString(R.string.create_task_send)
                        if (isLoading) {
                            binding.pageLoadingIndicator.visibility = android.view.View.VISIBLE
                        } else if (binding.contentContainer.visibility == android.view.View.VISIBLE) {
                            binding.pageLoadingIndicator.visibility = android.view.View.GONE
                        }
                    }
                }

                launch {
                    viewModel.isSourcesLoading.collectLatest { isLoading ->
                        if (isLoading) {
                            binding.pageLoadingIndicator.visibility = android.view.View.VISIBLE
                            binding.contentContainer.visibility = android.view.View.GONE
                        } else {
                            binding.pageLoadingIndicator.visibility = android.view.View.GONE
                            if (binding.contentContainer.visibility != android.view.View.VISIBLE) {
                                binding.contentContainer.alpha = 0f
                                binding.contentContainer.scaleX = 0.95f
                                binding.contentContainer.scaleY = 0.95f
                                binding.contentContainer.visibility = android.view.View.VISIBLE
                                binding.contentContainer.animate()
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(300)
                                    .start()
                            }
                        }
                    }
                }

                launch {
                    viewModel.availableBranches.collectLatest { branches ->
                        branchAdapter?.clear()
                        branchAdapter?.addAll(branches)
                        branchAdapter?.notifyDataSetChanged()
                    }
                }

                launch {
                    viewModel.selectedBranch.collectLatest { branch ->
                        if (branch != null) {
                            binding.branchInput.setText(branch, false)
                        } else {
                            binding.branchInput.setText("", false)
                        }
                    }
                }

                launch {
                    viewModel.isBranchesLoading.collectLatest { isLoading ->
                        if (isLoading) {
                            binding.branchInputLayout.hint = "Loading branches..."
                            binding.branchInputLayout.isEnabled = false
                        } else {
                            binding.branchInputLayout.hint = "Branch (Optional)"
                            binding.branchInputLayout.isEnabled = true
                        }
                    }
                }

                launch {
                    viewModel.errorEvent.collect { errorResId ->
                        Toast.makeText(this@CreateTaskActivity, errorResId, Toast.LENGTH_LONG).show()
                    }
                }

                launch {
                    viewModel.taskCreatedEvent.collect {
                        Toast.makeText(this@CreateTaskActivity, "Task started successfully", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }
    }

    private fun setupRepoSelector() {
        repoAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ArrayList())
        binding.repoInput.setAdapter(repoAdapter)

        branchAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ArrayList())
        binding.branchInput.setAdapter(branchAdapter)

        binding.repoInput.setOnItemClickListener { parent, _, position, _ ->
            val selectedDisplayName = parent.getItemAtPosition(position) as String
            val fullSource = sourceMap.getValue(selectedDisplayName)
            viewModel.onSourceSelected(fullSource)
        }

        binding.branchInput.setOnItemClickListener { parent, _, position, _ ->
            val selectedBranch = parent.getItemAtPosition(position) as String
            viewModel.onBranchSelected(selectedBranch)
        }

        binding.repoInput.addTextChangedListener {
            val hasRepo = !it.isNullOrBlank()
            TransitionManager.beginDelayedTransition(binding.contentContainer)
            binding.branchInputLayout.visibility = if (hasRepo) View.VISIBLE else View.INVISIBLE
            if (!hasRepo) {
                binding.branchInput.setText("")
            }
        }
        
        // Initial state check
        val initialHasRepo = !binding.repoInput.text.isNullOrBlank()
        binding.branchInputLayout.visibility = if (initialHasRepo) View.VISIBLE else View.INVISIBLE
    }


    private fun readAssetPrompt(filename: String): String? {
        return try {
            assets.open("prompts/$filename").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Error loading prompt: $filename", e)
            Toast.makeText(this, R.string.error_loading_prompt, Toast.LENGTH_SHORT).show()
            null
        }
    }

    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun setupPromptGallery() {
        if (!PreferenceUtils.isPromptGalleryEnabled(this)) {
            binding.tvPromptGalleryTitle.visibility = View.GONE
            binding.rvPromptGallery.visibility = View.GONE
            return
        }

        var touchHelper: ItemTouchHelper? = null

        promptAdapter = object : PromptAdapter(
            onItemClick = { item ->
                if (item.id != "custom_add") {
                    if (item.isCustom || !item.body.endsWith(".md")) {
                        binding.taskInput.setText(item.body)
                    } else {
                        readAssetPrompt(item.body)?.let { binding.taskInput.setText(it) }
                    }
                }
            },
            onCustomAddClick = {
                showAddCustomPromptDialog()
            },
            onItemsReordered = { newItems ->
                savePromptOrder(newItems)
            },
            onItemDisabled = { item ->
                disablePrompt(item)
            },
            onStartDrag = { viewHolder ->
                touchHelper?.startDrag(viewHolder)
            }
        ) {
            override fun onEditModeChanged(editMode: Boolean) {
                onBackPressedCallback.isEnabled = editMode
            }
        }
        val layoutManager = FlexboxLayoutManager(this).apply {
            flexDirection = FlexDirection.ROW
            justifyContent = JustifyContent.CENTER
            flexWrap = FlexWrap.WRAP
        }
        binding.rvPromptGallery.layoutManager = layoutManager
        binding.rvPromptGallery.adapter = promptAdapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (viewHolder.adapterPosition == RecyclerView.NO_POSITION ||
                    target.adapterPosition == RecyclerView.NO_POSITION
                ) return false

                if (promptAdapter.getItems()[viewHolder.adapterPosition].id == "custom_add" ||
                    promptAdapter.getItems()[target.adapterPosition].id == "custom_add"
                ) return false

                promptAdapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean {
                return false
            }
        })
        touchHelper.attachToRecyclerView(binding.rvPromptGallery)

        loadPrompts()
    }

    private fun loadPrompts() {
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

        val gson = Gson()

        // Load custom prompts
        val customPromptsJson = PreferenceUtils.getCustomPromptsJson(this)
        val customPrompts: List<PromptItem> = if (!customPromptsJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<PromptItem>>() {}.type
                gson.fromJson(customPromptsJson, type) ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Corrupted custom_prompts JSON, ignoring", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        // Load disabled prompts
        val disabledPromptsJson = PreferenceUtils.getDisabledPromptsJson(this)
        val disabledPrompts: Set<String> = if (!disabledPromptsJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<Set<String>>() {}.type
                gson.fromJson(disabledPromptsJson, type) ?: emptySet()
            } catch (e: Exception) {
                Log.w(TAG, "Corrupted disabled_prompts JSON, ignoring", e)
                emptySet()
            }
        } else {
            emptySet()
        }

        // Override defaults with edited custom prompts
        val mergedPrompts = defaultPrompts.map { defaultItem ->
            customPrompts.find { it.id == defaultItem.id } ?: defaultItem
        }.toMutableList()

        // Add true custom prompts
        mergedPrompts.addAll(customPrompts.filter { it.isCustom && mergedPrompts.none { mp -> mp.id == it.id } })

        val allPrompts = mergedPrompts.filter { !disabledPrompts.contains(it.id) }.toMutableList()

        // Apply saved order
        val promptOrderJson = PreferenceUtils.getPromptOrderJson(this)
        if (!promptOrderJson.isNullOrEmpty()) {
            val savedOrder: List<String> = try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(promptOrderJson, type) ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Corrupted prompt_order JSON, ignoring", e)
                emptyList()
            }

            val orderedPrompts = mutableListOf<PromptItem>()
            for (id in savedOrder) {
                val item = allPrompts.find { it.id == id }
                if (item != null) {
                    orderedPrompts.add(item)
                    allPrompts.remove(item)
                }
            }
            // Add any new/remaining prompts to the end
            orderedPrompts.addAll(allPrompts)
            allPrompts.clear()
            allPrompts.addAll(orderedPrompts)
        }

        allPrompts.add(PromptItem("custom_add", getString(R.string.prompt_custom_title), false, ""))

        promptAdapter.submitList(allPrompts)
    }

    private fun showAddCustomPromptDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_custom_prompt, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val etTitle = dialogView.findViewById<EditText>(R.id.etPromptTitle)
        val etEmoji = dialogView.findViewById<EditText>(R.id.etPromptEmoji)
        val etBody = dialogView.findViewById<EditText>(R.id.etPromptBody)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSavePrompt)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPrompt)

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
            val newCustomPrompt = PromptItem(
                id = "custom_${java.util.UUID.randomUUID()}",
                title = finalTitle,
                isCustom = true,
                body = body
            )

            saveCustomPrompt(newCustomPrompt)
            dialog.dismiss()
            loadPrompts() // Reload to show the new prompt
        }

        dialog.show()
    }

    private fun saveCustomPrompt(prompt: PromptItem) {
        val gson = Gson()
        val customPromptsJson = PreferenceUtils.getCustomPromptsJson(this)
        val customPrompts: MutableList<PromptItem> = if (!customPromptsJson.isNullOrEmpty()) {
            val type = object : TypeToken<MutableList<PromptItem>>() {}.type
            gson.fromJson(customPromptsJson, type)
        } else {
            mutableListOf()
        }

        customPrompts.add(prompt)
        PreferenceUtils.setCustomPromptsJson(this, gson.toJson(customPrompts))
    }

    private fun savePromptOrder(items: List<PromptItem>) {
        val order = items.filter { it.id != "custom_add" }.map { it.id }
        val gson = Gson()
        PreferenceUtils.setPromptOrderJson(this, gson.toJson(order))
    }

    private fun disablePrompt(item: PromptItem) {
        val gson = Gson()
        val disabledPromptsJson = PreferenceUtils.getDisabledPromptsJson(this)
        val disabledPrompts: MutableSet<String> = if (!disabledPromptsJson.isNullOrEmpty()) {
            val type = object : TypeToken<MutableSet<String>>() {}.type
            gson.fromJson(disabledPromptsJson, type)
        } else {
            mutableSetOf()
        }

        disabledPrompts.add(item.id)
        PreferenceUtils.setDisabledPromptsJson(this, gson.toJson(disabledPrompts))

        Toast.makeText(this, getString(R.string.toast_prompt_hidden), Toast.LENGTH_SHORT).show()

        // Remove from current adapter list without reloading everything, or reload
        loadPrompts()
    }

    private fun setupKeyboardFocusClear() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (!isImeVisible) {
                currentFocus?.clearFocus()
            }
            insets
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_UP) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }

            if (::promptAdapter.isInitialized && promptAdapter.isEditMode) {
                val outRect = android.graphics.Rect()
                binding.rvPromptGallery.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    promptAdapter.isEditMode = false
                    // Save prompt order implicitly by saving whatever is currently displayed
                    savePromptOrder(promptAdapter.getItems())
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupTaskInputExpansion() {
        binding.btnExpandTaskInput.setOnClickListener {
            toggleTaskInputExpansion()
        }

        binding.taskInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // If count > 1, it's a paste or programmatic insert, so don't auto-expand.
                // If count == 1, it's a single character typed by the user.
                if (count == 1 && binding.taskInput.hasFocus() && !isTaskInputExpanded) {
                    if (binding.taskInput.lineCount > 3 || (s?.length ?: 0) > 150) {
                        toggleTaskInputExpansion(forceExpand = true)
                    }
                }
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun toggleTaskInputExpansion(forceExpand: Boolean = false) {
        val expanding = forceExpand || !isTaskInputExpanded
        if (isTaskInputExpanded == expanding) return

        isTaskInputExpanded = expanding

        TransitionManager.beginDelayedTransition(binding.contentContainer)
        if (expanding) {
            binding.taskInput.maxLines = Integer.MAX_VALUE
            binding.btnExpandTaskInput.animate().rotation(180f).setDuration(ANIMATION_DURATION_MS).start()
        } else {
            binding.taskInput.maxLines = 3
            binding.btnExpandTaskInput.animate().rotation(0f).setDuration(ANIMATION_DURATION_MS).start()
        }
    }

    private fun setupVoiceInput() {
        if (!PreferenceUtils.isVoiceTypingEnabled(this) || !SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.btnVoiceInput.visibility = View.GONE
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this) ?: return
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        binding.btnVoiceInput.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_AUDIO)
            } else {
                showVoiceDialog()
            }
        }
    }

    private fun showVoiceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_voice_input, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val wavyIndicator = dialogView.findViewById<com.jules.loader.ui.widget.WavyVoiceIndicatorView>(R.id.wavy_voice_indicator)
        val tvTranscription = dialogView.findViewById<android.widget.TextView>(R.id.tv_transcription)
        val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.tv_listening_status)
        val btnCancel = dialogView.findViewById<android.view.View>(R.id.btn_cancel_voice)
        val btnDragDismiss = dialogView.findViewById<android.view.View>(R.id.btn_drag_dismiss)

        // Animated ellipsis: "Listening." → "Listening.." → "Listening..."
        var dotCount = 1
        val ellipsisRunnable = object : Runnable {
            override fun run() {
                tvStatus.text = "Listening" + ".".repeat(dotCount)
                dotCount = (dotCount % 3) + 1
                tvStatus.postDelayed(this, ELLIPSIS_INTERVAL_MS)
            }
        }

        // When the wave settles to flat after stopListening(), dismiss the sheet
        wavyIndicator.onSettledToFlat = {
            tvStatus.removeCallbacks(ellipsisRunnable)
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            tvStatus.removeCallbacks(ellipsisRunnable)
            speechRecognizer.stopListening()
            dialog.dismiss()
        }

        btnDragDismiss.setOnClickListener {
            tvStatus.removeCallbacks(ellipsisRunnable)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            tvStatus.removeCallbacks(ellipsisRunnable)
            speechRecognizer.stopListening()
            isListening = false
        }

        var pendingSettleRunnable: Runnable? = null

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            // RecognitionListener callbacks are dispatched on the main thread by Android's
            // SpeechRecognizer, so pendingSettleRunnable access is safe without synchronization.

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                isListening = true
                originalTextBeforeSpeech = binding.taskInput.text?.toString() ?: ""
                // Cancel any pending dismiss-settle from a previous result
                pendingSettleRunnable?.let { wavyIndicator.removeCallbacks(it) }
                pendingSettleRunnable = null
                // Start animated ellipsis
                dotCount = 1
                tvStatus.removeCallbacks(ellipsisRunnable)
                tvStatus.text = "Listening."
                tvStatus.postDelayed(ellipsisRunnable, ELLIPSIS_INTERVAL_MS)
                wavyIndicator.startListening()
            }
            override fun onRmsChanged(rmsdB: Float) {
                val clampedDb = rmsdB.coerceIn(MIN_DB_LEVEL, MAX_DB_LEVEL)
                val normalised = (clampedDb - MIN_DB_LEVEL) / DB_LEVEL_RANGE
                wavyIndicator.setAmplitude(normalised)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // User paused — return to gentle resting ripple while results are processed.
                // Do NOT settle to flat yet; the user may start speaking again.
                tvStatus.removeCallbacks(ellipsisRunnable)
                tvStatus.text = "Processing..."
                wavyIndicator.returnToResting()
            }
            override fun onError(error: Int) {
                isListening = false
                tvStatus.removeCallbacks(ellipsisRunnable)
                pendingSettleRunnable?.let { wavyIndicator.removeCallbacks(it) }
                pendingSettleRunnable = null
                tvStatus.text = "Error"
                dialog.dismiss()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    val newText = if (originalTextBeforeSpeech.isBlank()) spokenText else "$originalTextBeforeSpeech $spokenText"
                    binding.taskInput.setText(newText)
                    binding.taskInput.setSelection(newText.length)
                }
                tvStatus.removeCallbacks(ellipsisRunnable)
                tvStatus.text = "Done"
                // Delay 2 s so the user can read their transcription before the wave settles
                // and the sheet auto-dismisses. Settling to flat triggers onSettledToFlat → dismiss.
                val settleRunnable = Runnable { wavyIndicator.stopListening() }
                pendingSettleRunnable = settleRunnable
                wavyIndicator.postDelayed(settleRunnable, SETTLE_DISMISS_DELAY_MS)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    tvTranscription.text = matches[0]
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        dialog.setOnDismissListener {
            pendingSettleRunnable?.let { wavyIndicator.removeCallbacks(it) }
            pendingSettleRunnable = null
            tvStatus.removeCallbacks(ellipsisRunnable)
        }

        speechRecognizer.startListening(speechRecognizerIntent)
        dialog.show()
    }

}

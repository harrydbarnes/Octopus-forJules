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
import android.view.View
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

class CreateTaskActivity : BaseActivity() {

    private lateinit var binding: ActivityCreateTaskBinding
    private lateinit var viewModel: CreateTaskViewModel
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent
    private var isListening = false
    private var originalTextBeforeSpeech = ""
    private var repoAdapter: ArrayAdapter<String>? = null
    private var branchAdapter: ArrayAdapter<String>? = null
    private val sourceMap = mutableMapOf<String, String>()

    companion object {
        private val TAG = CreateTaskActivity::class.java.simpleName
        private const val PERMISSION_REQUEST_AUDIO = 100
        private const val MIN_DB_LEVEL = -2f
        private const val MAX_DB_LEVEL = 10f
        private const val DB_LEVEL_RANGE = MAX_DB_LEVEL - MIN_DB_LEVEL
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

        setupRepoSelector()
        setupVoiceInput()
        observeViewModel()

        binding.btnStartTask.setOnClickListener {
            val prompt = binding.taskInput.text.toString().trim()
            if (prompt.isNotEmpty()) {
                val repoInputText = binding.repoInput.text.toString().takeIf { it.isNotBlank() }
                val repo = if (repoInputText != null) {
                    sourceMap.getValue(repoInputText)
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

                        repoAdapter?.clear()
                        repoAdapter?.addAll(sourceNames)
                        repoAdapter?.notifyDataSetChanged()
                    }
                }

                launch {
                    viewModel.isLoading.collectLatest { isLoading ->
                        binding.btnStartTask.isEnabled = !isLoading
                        binding.btnStartTask.text = if (isLoading) getString(R.string.create_task_starting) else getString(R.string.create_task_send)
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
        binding.repoInput.setOnClickListener { binding.repoInput.showDropDown() }

        branchAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ArrayList())
        binding.branchInput.setAdapter(branchAdapter)
        binding.branchInput.setOnClickListener {
            if (!binding.repoInput.text.isNullOrBlank()) {
                binding.branchInput.showDropDown()
            }
        }

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

    private fun setupVoiceInput() {
        binding.taskInputLayout.setEndIconDrawable(R.drawable.ic_mic)
        binding.taskInputLayout.setEndIconContentDescription("Voice Input")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.taskInputLayout.isEndIconVisible = false
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this) ?: return
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        binding.taskInputLayout.setEndIconOnClickListener {
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

        val visualizer = dialogView.findViewById<android.view.View>(R.id.voice_visualizer)
        val tvTranscription = dialogView.findViewById<android.widget.TextView>(R.id.tv_transcription)
        val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.tv_listening_status)
        val btnCancel = dialogView.findViewById<android.view.View>(R.id.btn_cancel_voice)

        btnCancel.setOnClickListener {
            speechRecognizer.stopListening()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            speechRecognizer.stopListening()
            isListening = false
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                isListening = true
                originalTextBeforeSpeech = binding.taskInput.text?.toString() ?: ""
                tvStatus.text = "Listening..."
            }
            override fun onRmsChanged(rmsdB: Float) {
                // Scale visualizer based on dB
                val clampedDb = rmsdB.coerceIn(MIN_DB_LEVEL, MAX_DB_LEVEL)
                val scale = 1.0f + (clampedDb - MIN_DB_LEVEL) / DB_LEVEL_RANGE // Scale 1.0 to 2.0
                visualizer.animate().scaleX(scale).scaleY(scale).setDuration(50).start()
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvStatus.text = "Processing..."
            }
            override fun onError(error: Int) {
                isListening = false
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
                dialog.dismiss()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    tvTranscription.text = matches[0]
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(speechRecognizerIntent)
        dialog.show()
    }

}

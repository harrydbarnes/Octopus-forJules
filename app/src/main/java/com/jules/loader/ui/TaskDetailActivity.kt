package com.jules.loader.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.jules.loader.R
import com.jules.loader.data.JulesRepository
import com.jules.loader.data.model.ActivityLog
import com.jules.loader.util.PreferenceUtils
import com.jules.loader.databinding.ActivityTaskDetailBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TaskDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityTaskDetailBinding
    private lateinit var repository: JulesRepository
    private lateinit var logAdapter: LogAdapter
    private var sessionId: String? = null

    private var nextPageToken: String? = null
    private var lastLoadedPageToken: String? = null
    private var isLoadingMore = false
    private val allLogs = java.util.Collections.synchronizedList(java.util.ArrayList<ActivityLog>())
    private var currentPrUrl: String? = null

    companion object {
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_SESSION_TITLE = "EXTRA_SESSION_TITLE"
        const val EXTRA_SESSION_PROMPT = "EXTRA_SESSION_PROMPT"
        const val EXTRA_SESSION_STATUS = "EXTRA_SESSION_STATUS"
        const val EXTRA_SESSION_SOURCE = "EXTRA_SESSION_SOURCE"
        const val EXTRA_SESSION_BRANCH = "EXTRA_SESSION_BRANCH"
        const val STATUS_PR_OPEN = "PR Open"
        const val STATUS_EXECUTING_TESTS = "Executing Tests"
        private const val POLLING_INTERVAL_MS = 3000L

        val WORKING_TYPES = setOf("WORKING", "COMMITTING_CODE", "EXECUTING TESTS", "RUNNING TESTS")
        val TERMINAL_STATES = setOf("COMPLETED", "FAILED", "CANCELLED", "TERMINATED")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        setEnterSharedElementCallback(com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback())

        window.sharedElementEnterTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 300L
        }
        window.sharedElementReturnTransition = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = 250L
        }

        super.onCreate(savedInstanceState)
        binding = ActivityTaskDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = JulesRepository.getInstance(applicationContext)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        binding.root.transitionName = "shared_element_container_${sessionId}"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        populateSessionDetails()

        // Setup Log RecyclerView
        binding.logRecyclerView.layoutManager = LinearLayoutManager(this)
        logAdapter = LogAdapter()
        binding.logRecyclerView.adapter = logAdapter

        binding.logRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoadingMore && nextPageToken != null) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                        && firstVisibleItemPosition >= 0
                    ) {
                        loadMoreLogs()
                    }
                }
            }
        })

        sessionId?.let { id ->
            startPollingLogs(id)
        }

        binding.detailPrChip.setOnClickListener {
            val url = currentPrUrl
            if (!url.isNullOrBlank() && url.startsWith("https://")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.error_open_url), Toast.LENGTH_SHORT).show()
                    Log.e("TaskDetailActivity", "Error opening PR URL. No activity found to handle the intent.", e)
                }
            } else {
                Toast.makeText(this@TaskDetailActivity, getString(R.string.error_invalid_url), Toast.LENGTH_SHORT).show()
                Log.e("TaskDetailActivity", "Invalid PR URL: $url")
            }
        }

        binding.btnSend.setOnClickListener {
            val message = binding.inputMessage.text.toString().trim()
            val currentSessionId = sessionId
            if (message.isNotEmpty() && currentSessionId != null) {
                sendMessage(currentSessionId, message)
            }
        }
    }

    private fun sendMessage(sessionId: String, message: String) {
        lifecycleScope.launch {
            try {
                binding.btnSend.isEnabled = false
                val log = repository.createActivity(sessionId, message)
                binding.inputMessage.text?.clear()

                synchronized(allLogs) {
                    allLogs.add(log)
                    logAdapter.submitList(ArrayList(allLogs))
                }
            } catch (e: Exception) {
                Toast.makeText(this@TaskDetailActivity, getString(R.string.error_sending_message), Toast.LENGTH_SHORT).show()
                Log.e("TaskDetailActivity", "Error sending message", e)
            } finally {
                binding.btnSend.isEnabled = true
            }
        }
    }

    private fun cancelSession() {
        sessionId?.let { id ->
            lifecycleScope.launch {
                try {
                    val session = repository.cancelSession(id)
                    binding.detailStatusChip.text = session.status
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.message_session_cancelled), Toast.LENGTH_SHORT).show()
                    invalidateOptionsMenu()
                } catch (e: Exception) {
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.error_cancel_session), Toast.LENGTH_SHORT).show()
                    Log.e("TaskDetailActivity", "Error cancelling task", e)
                }
            }
        }
    }

    private fun pauseSession() {
        sessionId?.let { id ->
            lifecycleScope.launch {
                try {
                    // Map "Pause" to deleteSession per user instructions "use the existing delete session endpoint"
                    repository.deleteSession(id)
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.message_session_paused), Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.error_pause_session), Toast.LENGTH_SHORT).show()
                    Log.e("TaskDetailActivity", "Error pausing task", e)
                }
            }
        }
    }

    private fun archiveSession() {
        sessionId?.let { id ->
            lifecycleScope.launch {
                try {
                    // Map "Archive" to deleteSession per user instructions
                    repository.deleteSession(id)
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.message_session_archived), Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.error_archive_session), Toast.LENGTH_SHORT).show()
                    Log.e("TaskDetailActivity", "Error archiving task", e)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_task_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu?): Boolean {
        val status = binding.detailStatusChip.text.toString().uppercase(java.util.Locale.ROOT).replace(" ", "_")
        val isTerminal = TERMINAL_STATES.contains(status)

        menu?.findItem(R.id.action_archive)?.isVisible = isTerminal
        menu?.findItem(R.id.action_cancel)?.isVisible = !isTerminal
        menu?.findItem(R.id.action_pause)?.isVisible = !isTerminal

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_cancel -> {
                cancelSession()
                true
            }
            R.id.action_pause -> {
                pauseSession()
                true
            }
            R.id.action_archive -> {
                archiveSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun populateSessionDetails() {
        val title = intent.getStringExtra(EXTRA_SESSION_TITLE)
        val prompt = intent.getStringExtra(EXTRA_SESSION_PROMPT) ?: getString(R.string.no_prompt)
        val statusRaw = intent.getStringExtra(EXTRA_SESSION_STATUS) ?: getString(R.string.status_initialising)
        val status = statusRaw.replace("_", " ")
        val source = intent.getStringExtra(EXTRA_SESSION_SOURCE)
        val branch = intent.getStringExtra(EXTRA_SESSION_BRANCH)

        val fullTitle = title ?: getString(R.string.untitled_session)
        val words = fullTitle.split(" ")
        if (words.size > 10) {
            val truncatedTitle = words.take(10).joinToString(" ") + "..."
            binding.detailTitle.text = truncatedTitle
        } else {
            binding.detailTitle.text = fullTitle
        }

        binding.detailPrompt.text = prompt
        binding.detailStatusChip.text = status

        when (statusRaw) {
            STATUS_PR_OPEN -> binding.detailStatusChip.setChipBackgroundColorResource(R.color.status_pr_open)
            STATUS_EXECUTING_TESTS -> binding.detailStatusChip.setChipBackgroundColorResource(R.color.status_tests_passing)
            "COMPLETED" -> binding.detailStatusChip.setChipBackgroundColorResource(R.color.status_tests_passing)
            else -> binding.detailStatusChip.setChipBackgroundColorResource(R.color.jules_purple_light)
        }

        if (source != null) {
            binding.detailSourceChip.visibility = View.VISIBLE
            val cleanSource = source.removePrefix("sources/github/")
            val displaySource = PreferenceUtils.getDisplayRepoName(this, cleanSource)
            binding.detailSourceChip.text = displaySource
        } else {
            binding.detailSourceChip.visibility = View.GONE
        }

        if (branch != null) {
            binding.detailBranchChip.visibility = View.VISIBLE
            binding.detailBranchChip.text = branch
        } else {
            binding.detailBranchChip.visibility = View.GONE
        }
    }

    private fun startPollingLogs(id: String) {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    val session = repository.getSession(id)
                    val statusRaw = session.status ?: getString(R.string.status_idle)
                    binding.detailStatusChip.text = statusRaw.replace("_", " ")

                    val prOutput = session.outputs?.firstOrNull { it.pullRequest != null }?.pullRequest
                    if (prOutput != null) {
                        currentPrUrl = prOutput.url
                        binding.detailPrChip.visibility = View.VISIBLE
                        binding.detailPrChip.text = getString(R.string.view_pr)
                    } else {
                        currentPrUrl = null
                        binding.detailPrChip.visibility = View.GONE
                    }

                    if (!isLoadingMore) {
                        val response = repository.getActivities(id, pageToken = null)
                        addLogs(response.activities ?: emptyList(), prepend = false)

                        // Only set the initial token for backward pagination
                        if (nextPageToken == null) {
                            nextPageToken = response.nextPageToken
                        }
                    }

                    if (session.status != null && TERMINAL_STATES.contains(session.status.uppercase(java.util.Locale.ROOT))) {
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TaskDetailActivity", "Error polling logs", e)
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    private fun loadMoreLogs() {
        if (isLoadingMore || nextPageToken == null || sessionId == null) return
        isLoadingMore = true
        lifecycleScope.launch {
            try {
                val token = nextPageToken
                val response = repository.getActivities(sessionId!!, pageToken = token)

                // lastLoadedPageToken is less relevant now that polling always fetches latest,
                // but kept for consistency if we wanted to track pagination cursor.
                lastLoadedPageToken = token
                nextPageToken = response.nextPageToken

                addLogs(response.activities ?: emptyList(), prepend = true)
            } catch (e: Exception) {
                android.util.Log.e("TaskDetailActivity", "Error loading more logs", e)
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun addLogs(newLogs: List<ActivityLog>, prepend: Boolean) {
        synchronized(allLogs) {
            // Append new logs avoiding duplicates
            val existingIds = allLogs.mapNotNull { it.id }.toSet()
            val uniqueNewLogs = newLogs.filter { it.id == null || !existingIds.contains(it.id) }

            if (uniqueNewLogs.isNotEmpty()) {
                if (prepend) {
                    allLogs.addAll(0, uniqueNewLogs)
                } else {
                    allLogs.addAll(uniqueNewLogs)
                }
                logAdapter.submitList(ArrayList(allLogs))
            }
        }
    }

    class LogAdapter : ListAdapter<ActivityLog, LogAdapter.LogViewHolder>(LogDiffCallback()) {

        private val expandedItems = mutableSetOf<String>()

        class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardView: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.bubbleCard)
            val typeText: TextView = view.findViewById(R.id.logType)
            val progress: View = view.findViewById(R.id.logProgress)
            val descText: TextView = view.findViewById(R.id.logDescription)
            val timeText: TextView = view.findViewById(R.id.logTimestamp)
            val btnToggleExpand: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnToggleExpand)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val log = getItem(position)
            val type = log.getResolvedType().uppercase(java.util.Locale.ROOT)
            val fullDescription = log.getResolvedDescription() ?: ""
            val logId = log.id ?: position.toString()

            holder.typeText.text = type
            // Utilise the new chat timestamp format
            holder.timeText.text = com.jules.loader.util.DateUtils.formatChatTimestamp(log.timestamp) ?: log.timestamp ?: ""

            val isExpanded = expandedItems.contains(logId)
            var displayDescription = fullDescription
            var showToggleButton = false

            // Distinctive styling for Code Updates vs generic Chat
            if (type.contains("CODE") || type.contains("FILE") || type.contains("COMMITTING")) {
                holder.cardView.setCardBackgroundColor(holder.itemView.context.getColor(R.color.jules_purple_light))
                holder.descText.typeface = android.graphics.Typeface.MONOSPACE
            } else {
                // Determine attribute colour programmeatically or standard fallback
                val typedValue = android.util.TypedValue()
                holder.itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
                holder.cardView.setCardBackgroundColor(typedValue.data)
                holder.descText.typeface = android.graphics.Typeface.DEFAULT
            }

            // Collapse Logic for Reviews
            if (type.contains("REVIEW")) {
                if (!isExpanded) {
                    displayDescription = "Review details hidden."
                    showToggleButton = true
                    holder.btnToggleExpand.text = "Expand Review"
                } else {
                    showToggleButton = true
                    holder.btnToggleExpand.text = "Collapse Review"
                }
            }

            // Collapse Logic for Plans (show up to 3 list items)
            else if (type.contains("PLAN")) {
                val lines = fullDescription.split("\n")
                if (lines.size > 3 && !isExpanded) {
                    displayDescription = lines.take(3).joinToString("\n") + "\n..."
                    showToggleButton = true
                    holder.btnToggleExpand.text = "Show Full Plan"
                } else if (lines.size > 3) {
                    showToggleButton = true
                    holder.btnToggleExpand.text = "Show Less"
                }
            }

            holder.descText.text = displayDescription
            holder.progress.visibility = if (TaskDetailActivity.WORKING_TYPES.contains(type)) View.VISIBLE else View.GONE

            if (showToggleButton) {
                holder.btnToggleExpand.visibility = View.VISIBLE
                holder.btnToggleExpand.setOnClickListener {
                    if (isExpanded) {
                        expandedItems.remove(logId)
                    } else {
                        expandedItems.add(logId)
                    }
                    notifyItemChanged(position)
                }
            } else {
                holder.btnToggleExpand.visibility = View.GONE
                holder.btnToggleExpand.setOnClickListener(null)
            }
        }

        class LogDiffCallback : DiffUtil.ItemCallback<ActivityLog>() {
            override fun areItemsTheSame(oldItem: ActivityLog, newItem: ActivityLog): Boolean {
                val oldIdentifier = oldItem.name ?: oldItem.id
                val newIdentifier = newItem.name ?: newItem.id
                return if (oldIdentifier != null && newIdentifier != null) {
                    oldIdentifier == newIdentifier
                } else {
                    oldItem === newItem
                }
            }

            override fun areContentsTheSame(oldItem: ActivityLog, newItem: ActivityLog): Boolean {
                return oldItem == newItem
            }
        }
    }
}

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
    private var isApprovingPlan = false
    private val allLogs = java.util.Collections.synchronizedList(java.util.ArrayList<ActivityLog>())
    private var currentPrUrl: String? = null
    private var expandedItems = mutableSetOf<String>()

    companion object {
        private const val KEY_EXPANDED_ITEMS = "KEY_EXPANDED_ITEMS"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_SESSION_TITLE = "EXTRA_SESSION_TITLE"
        const val EXTRA_SESSION_PROMPT = "EXTRA_SESSION_PROMPT"
        const val EXTRA_SESSION_STATUS = "EXTRA_SESSION_STATUS"
        const val EXTRA_SESSION_SOURCE = "EXTRA_SESSION_SOURCE"
        const val EXTRA_SESSION_BRANCH = "EXTRA_SESSION_BRANCH"
        const val STATUS_PR_OPEN = "PR Open"
        const val STATUS_EXECUTING_TESTS = "Executing Tests"
        const val STATUS_AWAITING_PLAN_APPROVAL = "AWAITING_PLAN_APPROVAL"
        private const val POLLING_INTERVAL_MS = 3000L

        const val CONST_REVIEW_MARKER = "**Analysis and Reasoning:**"
        const val CONST_PLAN_COMPLETED_MARKER = "All plan steps have been successfully completed. Ready for submission."
        const val CONST_COMPILED_CORRECTLY_MARKER = "Ran tests and compiled successfully."
        const val CONST_RATING_MARKER = "### Final Rating: #Correct#"
        const val TYPE_PLAN_APPROVED = "PLAN APPROVED"

        private const val BULLET_GAP_DEFAULT = 8
        private const val BULLET_INDENT_DEFAULT = 24
        private const val BULLET_GAP_INDENTED = 8
        private const val BULLET_INDENT_INDENTED = 48
        private const val HEADER_SUBITEM_MARGIN = 32

        val WORKING_TYPES = setOf("WORKING", "COMMITTING_CODE", "EXECUTING TESTS", "RUNNING TESTS")
        val TERMINAL_STATES = setOf("COMPLETED", "FAILED", "CANCELLED", "TERMINATED")

        fun applyMarkdownFormatting(text: String): CharSequence {
            val spannableString = android.text.SpannableStringBuilder()
            val lines = text.split("\n")
            var afterBoldBullet = false  // after a bold-text line (for indenting plain bullets)
            var inHeaderSection = false  // after a "header" bold line (ends with just ":")

            for ((index, line) in lines.withIndex()) {
                if (index > 0) spannableString.append("\n")
                val trimmed = line.trimStart()

                when {
                    trimmed.isEmpty() -> {
                        afterBoldBullet = false
                        inHeaderSection = false
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        val content = trimmed.substring(2)
                        val isIndented = afterBoldBullet || inHeaderSection
                        val indent = if (isIndented) BULLET_INDENT_INDENTED else BULLET_INDENT_DEFAULT
                        val gapWidth = if (isIndented) BULLET_GAP_INDENTED else BULLET_GAP_DEFAULT
                        val start = spannableString.length
                        spannableString.append(applyInlineBold(content))
                        // LeadingMarginSpan indents the entire bullet block (including the marker)
                        spannableString.setSpan(
                            android.text.style.LeadingMarginSpan.Standard(indent, indent),
                            start,
                            spannableString.length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        // BulletSpan draws the dot within the indented block
                        spannableString.setSpan(
                            android.text.style.BulletSpan(gapWidth),
                            start,
                            spannableString.length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    trimmed.startsWith("**") -> {
                        // Determine what follows the bold text
                        val closingIdx = trimmed.indexOf("**", 2)
                        val afterBold = if (closingIdx >= 0) trimmed.substring(closingIdx + 2).trim() else ""
                        // "header" bold = line is bold text followed only by ":" (section header)
                        val isHeaderBold = afterBold == ":" || afterBold.isEmpty()

                        when {
                            isHeaderBold -> {
                                // Section header: render bold, not indented
                                spannableString.append(applyInlineBold(trimmed))
                                inHeaderSection = true
                                afterBoldBullet = false
                            }
                            inHeaderSection -> {
                                // Sub-item under section header: indent with leading margin
                                val start = spannableString.length
                                spannableString.append(applyInlineBold(trimmed))
                                spannableString.setSpan(
                                    android.text.style.LeadingMarginSpan.Standard(HEADER_SUBITEM_MARGIN),
                                    start,
                                    spannableString.length,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                afterBoldBullet = true
                            }
                            else -> {
                                // Regular bold line
                                spannableString.append(applyInlineBold(trimmed))
                                afterBoldBullet = true
                                inHeaderSection = false
                            }
                        }
                    }
                    else -> {
                        spannableString.append(applyInlineBold(line))
                        afterBoldBullet = false
                    }
                }
            }
            return spannableString
        }

        private fun applyInlineBold(text: String): CharSequence {
            val spannableString = android.text.SpannableStringBuilder()
            var currentIndex = 0
            val regex = Regex("\\*\\*(.*?)\\*\\*")
            val matches = regex.findAll(text)
            for (match in matches) {
                spannableString.append(text.substring(currentIndex, match.range.first))
                val boldText = match.groupValues[1]
                val start = spannableString.length
                spannableString.append(boldText)
                spannableString.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    spannableString.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                currentIndex = match.range.last + 1
            }
            spannableString.append(text.substring(currentIndex))
            return spannableString
        }
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

        if (savedInstanceState != null) {
            val savedItems = savedInstanceState.getStringArrayList(KEY_EXPANDED_ITEMS)
            if (savedItems != null) {
                expandedItems.addAll(savedItems)
            }
        }

        // Show the loading indicator immediately while logs are being fetched for the first time
        binding.logsLoadingIndicator.visibility = View.VISIBLE

        // Setup Log RecyclerView
        binding.logRecyclerView.layoutManager = LinearLayoutManager(this)
        logAdapter = LogAdapter(expandedItems) { logId, isExpanded ->
            if (isExpanded) {
                expandedItems.add(logId)
            } else {
                expandedItems.remove(logId)
            }
        }
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

        binding.btnApprovePlan.setOnClickListener {
            approvePlan()
        }

        binding.inputContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val containerHeight = bottom - top
            binding.logRecyclerView.setPadding(
                binding.logRecyclerView.paddingLeft,
                binding.logRecyclerView.paddingTop,
                binding.logRecyclerView.paddingRight,
                containerHeight
            )
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

    private fun approvePlan() {
        sessionId?.let { id ->
            lifecycleScope.launch {
                try {
                    isApprovingPlan = true
                    binding.btnApprovePlan.visibility = View.GONE
                    binding.approvePlanProgress.visibility = View.VISIBLE
                    repository.approvePlan(id)
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.plan_approval_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@TaskDetailActivity, getString(R.string.error_approve_plan), Toast.LENGTH_SHORT).show()
                    Log.e("TaskDetailActivity", "Error approving plan", e)
                } finally {
                    isApprovingPlan = false
                    binding.approvePlanProgress.visibility = View.GONE
                    binding.btnApprovePlan.visibility = View.VISIBLE
                    binding.btnApprovePlan.isEnabled = true
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_EXPANDED_ITEMS, ArrayList(expandedItems))
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

        binding.detailPrompt.text = applyMarkdownFormatting(prompt)
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
            var initialLoadDone = false
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

                    val awaitingApproval = session.status == STATUS_AWAITING_PLAN_APPROVAL
                    // Only update banner/button state when no approval is in-flight to avoid
                    // overriding the optimistic disable set by approvePlan().
                    if (!isApprovingPlan) {
                        binding.planApprovalContainer.visibility = if (awaitingApproval) View.VISIBLE else View.GONE
                        binding.btnApprovePlan.isEnabled = awaitingApproval
                    }

                    if (!isLoadingMore) {
                        val response = repository.getActivities(id, pageToken = null)
                        addLogs(response.activities ?: emptyList())

                        if (!initialLoadDone) {
                            initialLoadDone = true
                            binding.logsLoadingIndicator.visibility = View.GONE
                        }

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
                    if (!initialLoadDone) {
                        initialLoadDone = true
                        binding.logsLoadingIndicator.visibility = View.GONE
                    }
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

                addLogs(response.activities ?: emptyList())
            } catch (e: Exception) {
                android.util.Log.e("TaskDetailActivity", "Error loading more logs", e)
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun addLogs(newLogs: List<ActivityLog>) {
        // Pre-filter new logs outside the lock to minimize contention
        val validNewLogs = newLogs.filter { log ->
            val desc = log.getResolvedDescription()
            desc?.trim() != "No details"
        }

        if (validNewLogs.isEmpty()) return

        val snapshot = synchronized(allLogs) {
            val existingIdentifiers = allLogs.map { it.name ?: it.id }.filterNotNull().toSet()
            val uniqueNewLogs = validNewLogs.filter { log ->
                val identifier = log.name ?: log.id
                identifier == null || !existingIdentifiers.contains(identifier)
            }

            if (uniqueNewLogs.isEmpty()) return

            allLogs.addAll(uniqueNewLogs)
            // Keep allLogs sorted so any snapshot taken elsewhere (e.g., sendMessage) is consistent
            allLogs.sortBy { com.jules.loader.util.DateUtils.parseDate(it.timestamp)?.time ?: 0L }

            // Remove consecutive entries that resolve to the same type (e.g., two PLAN APPROVED in a row)
            val deduped = allLogs.fold(mutableListOf<ActivityLog>()) { acc, log ->
                val resolvedType = log.getResolvedType().uppercase(java.util.Locale.ROOT)
                val prevType = acc.lastOrNull()?.getResolvedType()?.uppercase(java.util.Locale.ROOT)
                if (resolvedType == TYPE_PLAN_APPROVED && resolvedType == prevType) {
                    // Keep only the first of consecutive PLAN APPROVED entries
                } else {
                    acc.add(log)
                }
                acc
            }
            ArrayList(deduped)
        }
        logAdapter.submitList(snapshot)
    }

    class LogAdapter(
        private val expandedItems: Set<String>,
        private val onToggleExpand: (String, Boolean) -> Unit
    ) : ListAdapter<ActivityLog, LogAdapter.LogViewHolder>(LogDiffCallback()) {

        data class LogDisplayData(val type: String, val description: String)
        data class ReviewDisplayData(val displayDescription: String, val showToggleButton: Boolean)
        data class PlanDisplayData(val isPlanConfigured: Boolean, val showToggleButton: Boolean)

        class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardView: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.bubbleCard)
            val typeText: TextView = view.findViewById(R.id.logType)
            val typeIcon: android.widget.ImageView = view.findViewById(R.id.logTypeIcon)
            val typeIconEnd: android.widget.ImageView = view.findViewById(R.id.logTypeIconEnd)
            val progress: View = view.findViewById(R.id.logProgress)
            val descText: TextView = view.findViewById(R.id.logDescription)
            val timeText: TextView = view.findViewById(R.id.logTimestamp)
            val btnToggleExpand: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnToggleExpand)
            val planStepsRecyclerView: RecyclerView = view.findViewById(R.id.planStepsRecyclerView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val log = getItem(position)
            val logId = log.name ?: log.id
            val isExpanded = logId != null && expandedItems.contains(logId)

            val logData = resolveTypeAndDescription(log)
            val type = logData.type
            val fullDescription = logData.description

            holder.typeText.text = type
            holder.timeText.text = com.jules.loader.util.DateUtils.formatChatTimestamp(log.timestamp) ?: log.timestamp ?: ""
            holder.progress.visibility = if (TaskDetailActivity.WORKING_TYPES.contains(type)) View.VISIBLE else View.GONE

            applyCardStyling(holder, type)

            // Default visibility states
            holder.descText.visibility = View.VISIBLE
            holder.planStepsRecyclerView.visibility = View.GONE
            holder.typeIconEnd.visibility = View.GONE

            when {
                type == TaskDetailActivity.TYPE_PLAN_APPROVED -> {
                    bindPlanApprovedLog(holder, logId, position)
                }
                type.contains("REVIEW") -> {
                    bindReviewLog(holder, isExpanded, fullDescription, logId, position)
                }
                type.contains("PLAN") && type != "ALL PLAN STEPS COMPLETED" -> {
                    bindPlanLog(holder, fullDescription, isExpanded, logId, position)
                }
                else -> {
                    bindDefaultLog(holder, fullDescription, logId, position)
                }
            }
        }

        private fun bindPlanApprovedLog(holder: LogViewHolder, logId: String?, position: Int) {
            holder.descText.visibility = View.GONE
            holder.typeIconEnd.visibility = View.VISIBLE
            holder.descText.text = ""
            setupToggleButton(holder, false, logId, position)
        }

        private fun bindReviewLog(holder: LogViewHolder, isExpanded: Boolean, fullDescription: String, logId: String?, position: Int) {
            val reviewData = bindReviewData(holder, isExpanded, fullDescription)
            holder.descText.text = applyMarkdownFormatting(reviewData.displayDescription)
            setupToggleButton(holder, reviewData.showToggleButton, logId, position)
        }

        private fun bindPlanLog(holder: LogViewHolder, fullDescription: String, isExpanded: Boolean, logId: String?, position: Int) {
            val planData = bindPlanData(holder, fullDescription, isExpanded)
            if (planData.isPlanConfigured) {
                holder.descText.text = "" // Handled by RecyclerView
            } else {
                holder.descText.text = applyMarkdownFormatting(fullDescription)
            }
            setupToggleButton(holder, planData.showToggleButton, logId, position)
        }

        private fun bindDefaultLog(holder: LogViewHolder, fullDescription: String, logId: String?, position: Int) {
            holder.descText.text = applyMarkdownFormatting(fullDescription)
            setupToggleButton(holder, false, logId, position)
        }

        private fun resolveTypeAndDescription(log: ActivityLog): LogDisplayData {
            var type = log.getResolvedType().uppercase(java.util.Locale.ROOT)
            var fullDescription = log.getResolvedDescription() ?: ""

            fullDescription = fullDescription.replace(CONST_RATING_MARKER, "").trim()

            when {
                fullDescription.contains(CONST_REVIEW_MARKER) || type.contains("REVIEW") -> type = "CODE REVIEW"
                fullDescription.contains(CONST_PLAN_COMPLETED_MARKER) -> type = "ALL PLAN STEPS COMPLETED"
                fullDescription.contains(CONST_COMPILED_CORRECTLY_MARKER) -> type = "COMPILED CORRECTLY"
            }
            return LogDisplayData(type, fullDescription)
        }

        private fun isCodeTypeLog(type: String): Boolean {
            val codeKeywords = setOf("CODE", "FILE", "COMMITTING")
            return if (type.contains("REVIEW")) {
                false
            } else {
                codeKeywords.any { type.contains(it) }
            }
        }

        private fun applyCardStyling(holder: LogViewHolder, type: String) {
            if (isCodeTypeLog(type)) {
                holder.cardView.setCardBackgroundColor(holder.itemView.context.getColor(R.color.jules_purple_light))
                holder.descText.typeface = android.graphics.Typeface.MONOSPACE
            } else {
                val typedValue = android.util.TypedValue()
                holder.itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
                holder.cardView.setCardBackgroundColor(typedValue.data)
                holder.descText.typeface = android.graphics.Typeface.DEFAULT
            }
            holder.typeIcon.visibility = if (type == "CODE REVIEW") View.VISIBLE else View.GONE
        }

        private fun bindReviewData(holder: LogViewHolder, isExpanded: Boolean, fullDescription: String): ReviewDisplayData {
            var displayDescription = ""
            val showToggleButton = true
            if (!isExpanded) {
                holder.btnToggleExpand.text = "Expand Review"
                holder.btnToggleExpand.setIconResource(R.drawable.ic_expand_more)
            } else {
                displayDescription = fullDescription
                holder.btnToggleExpand.text = "Collapse Review"
                holder.btnToggleExpand.setIconResource(R.drawable.ic_expand_less)
            }
            return ReviewDisplayData(displayDescription, showToggleButton)
        }

        private fun bindPlanData(holder: LogViewHolder, fullDescription: String, isExpanded: Boolean): PlanDisplayData {
            val regex = Regex("(?m)^(?:\\[?(?:\\d+\\.|[-*])\\]?)\\s+(.*?)(?=\\n^(?:\\[?(?:\\d+\\.|[-*])\\]?)\\s+|$)", RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(fullDescription).toList()

            val parsedSteps: List<Pair<String, String>> = if (matches.isNotEmpty()) {
                matches.mapIndexed { index, matchResult ->
                    (index + 1).toString() to matchResult.groupValues[1].trim()
                }
            } else {
                // Fall back to treating each non-empty line as a plan step
                fullDescription.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapIndexed { index, line -> (index + 1).toString() to line }
            }

            if (parsedSteps.isNotEmpty()) {
                holder.descText.visibility = View.GONE
                holder.planStepsRecyclerView.visibility = View.VISIBLE
                holder.planStepsRecyclerView.layoutManager = LinearLayoutManager(holder.itemView.context)

                if (holder.planStepsRecyclerView.itemDecorationCount == 0) {
                    val divider = androidx.recyclerview.widget.DividerItemDecoration(holder.itemView.context, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL)
                    holder.planStepsRecyclerView.addItemDecoration(divider)
                }

                val needsToggle = parsedSteps.size > 3

                if (needsToggle && !isExpanded) {
                    holder.planStepsRecyclerView.adapter = PlanStepAdapter(parsedSteps.take(3))
                    holder.btnToggleExpand.text = "Show Full Plan"
                    holder.btnToggleExpand.setIconResource(R.drawable.ic_expand_more)
                } else {
                    holder.planStepsRecyclerView.adapter = PlanStepAdapter(parsedSteps)
                    if (needsToggle) {
                        holder.btnToggleExpand.text = "Show Less"
                        holder.btnToggleExpand.setIconResource(R.drawable.ic_expand_less)
                    }
                }
                return PlanDisplayData(isPlanConfigured = true, showToggleButton = needsToggle)
            }
            return PlanDisplayData(isPlanConfigured = false, showToggleButton = false)
        }

        private fun setupToggleButton(holder: LogViewHolder, showToggleButton: Boolean, logId: String?, position: Int) {
            if (showToggleButton && logId != null) {
                holder.btnToggleExpand.visibility = View.VISIBLE
                holder.btnToggleExpand.setOnClickListener {
                    val currentlyExpanded = expandedItems.contains(logId)
                    onToggleExpand(logId, !currentlyExpanded)
                    notifyItemChanged(position)
                }
            } else {
                holder.btnToggleExpand.visibility = View.GONE
                holder.btnToggleExpand.setOnClickListener(null)
            }
        }

        class PlanStepAdapter(private val steps: List<Pair<String, String>>) : RecyclerView.Adapter<PlanStepAdapter.PlanStepViewHolder>() {
            class PlanStepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val number: TextView = view.findViewById(R.id.planStepNumber)
                val text: TextView = view.findViewById(R.id.planStepText)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanStepViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plan_step, parent, false)
                return PlanStepViewHolder(view)
            }

            override fun onBindViewHolder(holder: PlanStepViewHolder, position: Int) {
                val step = steps[position]
                holder.number.text = step.first
                holder.text.text = step.second
            }

            override fun getItemCount() = steps.size
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

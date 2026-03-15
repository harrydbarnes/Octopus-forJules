package com.jules.loader

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jules.loader.data.JulesRepository
import com.jules.loader.data.model.Session
import com.jules.loader.databinding.ActivityMainBinding
import com.jules.loader.ui.BaseActivity
import com.jules.loader.ui.OnboardingActivity
import com.jules.loader.ui.SessionAdapter
import com.jules.loader.util.DateUtils
import com.jules.loader.util.PreferenceUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: JulesRepository
    private lateinit var adapter: SessionAdapter
    private var allSessions: List<Session> = emptyList()

    private var selectedRepo: String? = null
    private var selectedStatus: String? = null
    private var selectedDateRange: String? = null
    private var searchQuery: String = ""
    private var isFilterActive = false

    private var nextPageToken: String? = null
    private var isLoadingMore = false
    private var shimmerAnimators: List<ObjectAnimator> = emptyList()
    private var retryJob: Job? = null
    /** Tracks the active loadSessions coroutine so it can be cancelled in onPause(). */
    private var loadSessionsJob: Job? = null

    /** Triggers an immediate reload when the device regains network access. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (binding.errorContainer.visibility == View.VISIBLE) {
                    retryJob?.cancel()
                    loadSessions(forceRefresh = true)
                }
            }
        }
    }

    companion object {
        private const val KEY_SESSIONS = "key_sessions"
        private const val KEY_NEXT_PAGE_TOKEN = "key_next_page_token"
        private const val KEY_STOP_TIME = "key_stop_time"
        private const val REFRESH_TIMEOUT_MS = 20000L
        /** Intent extra: when `true`, immediately shows the no-signal error/game overlay. */
        const val EXTRA_SIMULATE_NO_SIGNAL = "simulate_no_signal"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = JulesRepository.getInstance(applicationContext)

        if (repository.getApiKey().isNullOrEmpty()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.sessions_title)

        adapter = SessionAdapter()
        binding.sessionsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sessionsRecyclerView.adapter = adapter

        // Set default item animator to ensure add/remove animations are smooth
        binding.sessionsRecyclerView.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()

        val cornerRadius = resources.getDimension(R.dimen.swipe_corner_radius)
        val paint = android.graphics.Paint().apply {
            color = androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.archive_red) // Archive Red
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        val icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_archive_session)
        val iconMargin = resources.getDimensionPixelSize(R.dimen.swipe_icon_margin)
        val background = android.graphics.RectF()

        val swipeHandler = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(r: androidx.recyclerview.widget.RecyclerView, v: androidx.recyclerview.widget.RecyclerView.ViewHolder, t: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean = false

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView

                    background.set(
                        itemView.left.toFloat(),
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat()
                    )
                    
                    c.save()
                    c.drawRoundRect(background, cornerRadius, cornerRadius, paint)

                    if (icon != null) {
                        val swipeProgress = kotlin.math.abs(dX) / itemView.width
                        val scale = (0.5f + (swipeProgress * 2.0f)).coerceAtMost(1.5f)
                        
                        val iconWidth = (icon.intrinsicWidth * scale).toInt()
                        val iconHeight = (icon.intrinsicHeight * scale).toInt()
                        
                        val iconCenterY = (itemView.top + itemView.bottom) / 2
                        val iconTopScaled = iconCenterY - iconHeight / 2
                        val iconBottomScaled = iconCenterY + iconHeight / 2

                        val iconLeft: Int
                        val iconRight: Int
                        
                        if (dX > 0) { // Swiping Right
                             iconLeft = itemView.left + iconMargin
                             iconRight = itemView.left + iconMargin + iconWidth
                        } else { // Swiping Left
                             iconLeft = itemView.right - iconMargin - iconWidth
                             iconRight = itemView.right - iconMargin
                        }

                        if (iconLeft < iconRight) {
                            icon.setBounds(iconLeft, iconTopScaled, iconRight, iconBottomScaled)
                            icon.draw(c)
                        }
                    }
                    c.restore()
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val session = adapter.currentList[position]
                confirmArchiveSession(session, position)
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.sessionsRecyclerView)

        binding.sessionsRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    binding.fab.shrink()
                } else if (dy < 0) {
                    binding.fab.extend()
                }

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoadingMore && nextPageToken != null) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                        && firstVisibleItemPosition >= 0
                    ) {
                        loadMoreSessions()
                    }
                }
            }
        })

        binding.fab.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) // Haptic
            val intent = Intent(this, com.jules.loader.ui.CreateTaskActivity::class.java)
            val options = android.app.ActivityOptions.makeSceneTransitionAnimation(
                this, binding.fab, "shared_element_container"
            )
            startActivity(intent, options.toBundle())
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadSessions(forceRefresh = true)
        }

        setupSearch()
        setupFilters()

        if (savedInstanceState != null) {
            val stopTime = savedInstanceState.getLong(KEY_STOP_TIME, 0)
            val currentTime = System.currentTimeMillis()
            if (currentTime - stopTime < REFRESH_TIMEOUT_MS) {
                val restoredSessions = savedInstanceState.getParcelableArrayList<Session>(KEY_SESSIONS)
                if (restoredSessions != null) {
                    allSessions = restoredSessions
                    nextPageToken = savedInstanceState.getString(KEY_NEXT_PAGE_TOKEN)
                    
                    binding.sessionsRecyclerView.visibility = View.VISIBLE
                    binding.skeletonLayout.visibility = View.GONE
                    binding.errorContainer.visibility = View.GONE
                    applyFilters()
                } else {
                    loadSessions()
                }
            } else {
                loadSessions()
            }
        } else {
            loadSessions()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle simulate-no-signal from Settings when the activity is already running
        handleSimulateNoSignal(intent)
    }

    override fun onResume() {
        super.onResume()
        val shortenRepoNames = PreferenceUtils.isShortenRepoNamesEnabled(this)
        if (::adapter.isInitialized) {
            adapter.isShortenRepoNamesEnabled = shortenRepoNames
            adapter.isShortenDatesEnabled = PreferenceUtils.isShortenDatesEnabled(this)
            adapter.isDateFormatMMDD = PreferenceUtils.isDateFormatMMDD(this)
            adapter.notifyDataSetChanged()
        }

        selectedRepo?.let { repo ->
            val displayRepo = PreferenceUtils.getDisplayRepoName(repo, shortenRepoNames)
            binding.chipRepo.text = displayRepo
        }

        // Debug: simulate no-signal error state from Settings
        handleSimulateNoSignal(intent)

        // Register network-available listener so we reload the moment signal returns
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not register network callback", e)
        }
    }

    override fun onPause() {
        super.onPause()
        retryJob?.cancel()
        retryJob = null
        // Cancel any in-flight loadSessions coroutine to prevent it from calling
        // showErrorWithGame() while the activity is paused.
        loadSessionsJob?.cancel()
        loadSessionsJob = null
        // Stop the game when leaving this activity to free Choreographer resources.
        if (binding.errorContainer.visibility == View.VISIBLE) {
            binding.octopusErrorGame.stopGame()
        }
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not unregister network callback", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        retryJob?.cancel()
        loadSessionsJob?.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(KEY_SESSIONS, ArrayList(allSessions))
        outState.putString(KEY_NEXT_PAGE_TOKEN, nextPageToken)
        outState.putLong(KEY_STOP_TIME, System.currentTimeMillis())
    }

    private fun setupSearch() {
        binding.btnSearch.contentDescription = getString(R.string.action_search)
        binding.btnSearch.setOnClickListener {
            TransitionManager.beginDelayedTransition(binding.root as ViewGroup, AutoTransition())
            binding.filterContainer.visibility = View.GONE
            binding.searchContainer.visibility = View.VISIBLE
            binding.searchEditText.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }

        binding.btnSearchBack.contentDescription = getString(R.string.action_search_close)
        binding.btnSearchBack.setOnClickListener {
            TransitionManager.beginDelayedTransition(binding.root as ViewGroup, AutoTransition())
            binding.searchContainer.visibility = View.GONE
            binding.filterContainer.visibility = View.VISIBLE
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
            binding.searchEditText.setText("") // Clear search on close
        }

        binding.btnSearchClear.contentDescription = getString(R.string.action_search_clear)
        binding.btnSearchClear.setOnClickListener {
            binding.searchEditText.setText("")
        }

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s.toString()
                applyFilters()
            }
        })
    }

    private fun setupFilters() {
        val touchListener = View.OnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                when (v.id) {
                    R.id.chipRepo -> showRepoMenu(v)
                    R.id.chipStatus -> showStatusMenu(v)
                    R.id.chipDate -> showDateMenu(v)
                }
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            }
            true
        }

        binding.chipRepo.setOnTouchListener(touchListener)
        binding.chipStatus.setOnTouchListener(touchListener)
        binding.chipDate.setOnTouchListener(touchListener)
    }

    private fun showRepoMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val repos = allSessions.mapNotNull { it.sourceContext?.cleanSource }
            .distinct()
            .sorted()

        val shortenRepoNames = PreferenceUtils.isShortenRepoNamesEnabled(this)
        popup.menu.add(0, 0, 0, getString(R.string.filter_all))
        repos.forEachIndexed { index, repo ->
            val displayRepo = PreferenceUtils.getDisplayRepoName(repo, shortenRepoNames)
            popup.menu.add(0, index + 1, index + 1, displayRepo)
        }

        popup.setOnMenuItemClickListener { item ->
            selectedRepo = if (item.itemId == 0) null else repos[item.itemId - 1]
            val displayRepo = selectedRepo?.let { repo ->
                PreferenceUtils.getDisplayRepoName(repo, shortenRepoNames)
            } ?: getString(R.string.filter_repo)
            binding.chipRepo.text = displayRepo
            binding.chipRepo.isChecked = selectedRepo != null
            applyFilters()
            true
        }
        popup.show()
    }

    private fun showStatusMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val statuses = allSessions.mapNotNull { it.status }.distinct().sorted()

        popup.menu.add(0, 0, 0, getString(R.string.filter_all))
        statuses.forEachIndexed { index, status ->
            popup.menu.add(0, index + 1, index + 1, status)
        }

        popup.setOnMenuItemClickListener { item ->
            selectedStatus = if (item.itemId == 0) null else statuses[item.itemId - 1]
            binding.chipStatus.text = selectedStatus ?: getString(R.string.filter_status)
            binding.chipStatus.isChecked = selectedStatus != null
            applyFilters()
            true
        }
        popup.show()
    }

    private fun showDateMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, getString(R.string.filter_all))
        popup.menu.add(0, 1, 1, getString(R.string.date_today))
        popup.menu.add(0, 2, 2, getString(R.string.date_yesterday))
        popup.menu.add(0, 3, 3, getString(R.string.date_7_days))
        popup.menu.add(0, 4, 4, getString(R.string.date_30_days))

        popup.setOnMenuItemClickListener { item ->
            selectedDateRange = if (item.itemId == 0) null else item.title.toString()
            binding.chipDate.text = selectedDateRange ?: getString(R.string.filter_date)
            binding.chipDate.isChecked = selectedDateRange != null
            applyFilters()
            true
        }
        popup.show()
    }

    private fun applyFilters() {
        var filtered = allSessions

        // 1. Search Filter
        if (searchQuery.isNotEmpty()) {
            val query = searchQuery.lowercase(Locale.getDefault())
            filtered = filtered.filter { session ->
                (session.title?.lowercase(Locale.getDefault())?.contains(query) == true) ||
                (session.prompt?.lowercase(Locale.getDefault())?.contains(query) == true)
            }
        }

        // 2. Repo Filter
        selectedRepo?.let { repo ->
             filtered = filtered.filter {
                 it.sourceContext?.cleanSource == repo
             }
        }

        // 3. Status Filter
        selectedStatus?.let { status ->
            filtered = filtered.filter { it.status == status }
        }

        // 4. Date Filter
        selectedDateRange?.let { range ->
            val now = Calendar.getInstance()
            resetTime(now)
            filtered = filtered.filter { session ->
                val date = DateUtils.parseDate(session.createTime)
                if (date != null) {
                    val sessionCal = Calendar.getInstance()
                    sessionCal.time = date
                    resetTime(sessionCal)

                    val diff = now.timeInMillis - sessionCal.timeInMillis
                    val daysDiff = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff)

                    when (range) {
                        getString(R.string.date_today) -> daysDiff == 0L
                        getString(R.string.date_yesterday) -> daysDiff == 1L
                        getString(R.string.date_7_days) -> daysDiff in 0..7
                        getString(R.string.date_30_days) -> daysDiff in 0..30
                        else -> true
                    }
                } else {
                    false
                }
            }
        }

        adapter.submitList(filtered)
        updateFilterIcon()
    }

    private fun resetTime(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun updateFilterIcon() {
        val hasFilter = selectedRepo != null || selectedStatus != null || selectedDateRange != null
        if (hasFilter == isFilterActive) return

        isFilterActive = hasFilter
        val iconRes = if (hasFilter) R.drawable.ic_close else R.drawable.ic_filter_list
        val desc = if (hasFilter) getString(R.string.clear_filters) else getString(R.string.filters_label)

        val width = binding.btnFilter.width.toFloat()
        val outEnd = if (hasFilter) -width else width
        val inStart = if (hasFilter) width else -width

        val outAnim = ObjectAnimator.ofFloat(binding.btnFilter, "translationX", 0f, outEnd)
        outAnim.duration = 150
        outAnim.interpolator = AccelerateDecelerateInterpolator()
        outAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.btnFilter.setImageResource(iconRes)
                binding.btnFilter.contentDescription = desc
                if (hasFilter) {
                    binding.btnFilter.setOnClickListener { clearFilters() }
                    binding.btnFilter.isClickable = true
                } else {
                    binding.btnFilter.setOnClickListener(null)
                    binding.btnFilter.isClickable = false
                }

                val inAnim = ObjectAnimator.ofFloat(binding.btnFilter, "translationX", inStart, 0f)
                inAnim.duration = 150
                inAnim.interpolator = AccelerateDecelerateInterpolator()
                inAnim.start()
            }
        })
        outAnim.start()
    }

    private fun clearFilters() {
        selectedRepo = null
        selectedStatus = null
        selectedDateRange = null

        binding.chipRepo.text = getString(R.string.filter_repo)
        binding.chipRepo.isChecked = false

        binding.chipStatus.text = getString(R.string.filter_status)
        binding.chipStatus.isChecked = false

        binding.chipDate.text = getString(R.string.filter_date)
        binding.chipDate.isChecked = false

        applyFilters()
    }

    private fun confirmArchiveSession(session: Session, position: Int) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_archive_title)
            .setMessage(R.string.dialog_archive_message)
            .setPositiveButton(R.string.action_archive) { _, _ ->
                archiveSession(session, position)
            }
            .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                adapter.notifyItemChanged(position)
                dialog.dismiss()
            }
            .setOnCancelListener { 
                adapter.notifyItemChanged(position)
            }
            .show()
    }

    private fun archiveSession(session: Session, position: Int) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padLarge = resources.getDimensionPixelSize(R.dimen.dialog_padding_large)
            setPadding(padLarge, padLarge, padLarge, padLarge)
            addView(com.google.android.material.progressindicator.CircularProgressIndicator(this@MainActivity).apply {
                isIndeterminate = true
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.dialog_wait_message)
                val padStart = resources.getDimensionPixelSize(R.dimen.dialog_text_padding_start)
                setPadding(padStart, 0, 0, 0)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
        }

        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_archiving_title)
            .setView(layout)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                // Use deleteSession as per "Don't change API call" requirement
                repository.deleteSession(session.id)
                allSessions = allSessions.filter { it.id != session.id }
                applyFilters()
                Snackbar.make(binding.root, R.string.message_session_archived, Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error archiving session", e)
                adapter.notifyItemChanged(position)
                Snackbar.make(binding.root, R.string.error_archive_session, Snackbar.LENGTH_SHORT).show()
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, com.jules.loader.ui.SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, com.jules.loader.ui.AboutActivity::class.java))
                true
            }
            R.id.action_gift -> {
                val url = "https://jules.google/docs/changelog"
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(this, Uri.parse(url))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSessions(forceRefresh: Boolean = false) {
        retryJob?.cancel()
        retryJob = null
        loadSessionsJob?.cancel()
        val isFirstLoad = !forceRefresh && !repository.hasCachedSessions()
        if (isFirstLoad) {
            binding.skeletonLayout.visibility = View.VISIBLE
            startSkeletonShimmer()
            binding.errorContainer.visibility = View.GONE
            binding.sessionsRecyclerView.visibility = View.GONE
        }

        // Show reload spinner inside the game overlay if it's already on screen
        if (forceRefresh && binding.errorContainer.visibility == View.VISIBLE) {
            binding.reloadingIndicator.visibility = View.VISIBLE
        }

        loadSessionsJob = lifecycleScope.launch {
            try {
                isLoadingMore = true
                val response = repository.getSessions(pageToken = null, forceRefresh = forceRefresh)
                allSessions = response.sessions ?: emptyList()
                nextPageToken = response.nextPageToken

                if (allSessions.isEmpty()) {
                    binding.octopusErrorGame.visibility = View.GONE
                    binding.errorSignalMessage.visibility = View.GONE
                    binding.gameBottomArea.visibility = View.GONE
                    binding.errorContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // Use theme's on-surface-variant colour so text is readable on the plain background
                    val tv = android.util.TypedValue()
                    if (theme.resolveAttribute(
                            com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true)) {
                        binding.errorText.setTextColor(tv.data)
                    }
                    binding.errorText.text = getString(R.string.no_sessions)
                    binding.errorContainer.visibility = View.VISIBLE
                    binding.sessionsRecyclerView.visibility = View.GONE
                    adapter.submitList(emptyList())
                } else {
                    hideErrorOverlay()
                    binding.sessionsRecyclerView.visibility = View.VISIBLE
                    applyFilters()
                    if (isFirstLoad) {
                        binding.sessionsRecyclerView.scheduleLayoutAnimation()
                    }
                }
            } catch (e: java.io.IOException) {
                showErrorWithGame(getString(R.string.error_loading_sessions, e.localizedMessage), isNetworkError = true)
                android.util.Log.e("MainActivity", "Error loading sessions", e)
                // Poor signal: auto-retry every 10s if network is still available
                scheduleAutoRetry()
            } catch (e: retrofit2.HttpException) {
                showErrorWithGame(getString(R.string.error_loading_sessions, e.message()))
                android.util.Log.e("MainActivity", "Error loading sessions", e)
                // Server error with network: don't auto-retry (not a signal issue)
            } finally {
                binding.skeletonLayout.visibility = View.GONE
                stopSkeletonShimmer()
                binding.swipeRefresh.isRefreshing = false
                binding.reloadingIndicator.visibility = View.GONE
                isLoadingMore = false
            }
        }
    }

    private fun scheduleAutoRetry() {
        // Only auto-retry when the device actually has a network connection.
        // If not (airplane mode / data off / no WiFi), skip — user must pull-to-refresh.
        if (!isNetworkAvailable()) return
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            delay(10_000L)
            if (isNetworkAvailable()) {
                loadSessions(forceRefresh = true)
            }
            // If network disappeared during the delay, do nothing (user pull-to-refresh)
        }
    }

    /** Returns true when the device has an active network (even if signal is poor). */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Checks the given [intent] for [EXTRA_SIMULATE_NO_SIGNAL] and shows the game overlay if
     * present. Called from both [onResume] and [onNewIntent] so the debug flow works whether
     * the activity is cold-started or already running.
     */
    private fun handleSimulateNoSignal(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SIMULATE_NO_SIGNAL, false)) {
            // Clear the extra so re-entry (e.g. screen rotation) doesn't re-trigger
            intent.putExtra(EXTRA_SIMULATE_NO_SIGNAL, false)
            showErrorWithGame(getString(R.string.debug_simulate_no_signal_message), isNetworkError = true)
        }
    }

    private fun hideErrorOverlay() {
        // Only perform cleanup when the overlay is actually visible; skip on initial/normal loads.
        if (binding.errorContainer.visibility != View.VISIBLE) return
        binding.octopusErrorGame.stopGame()

        // Animate the overlay fading out smoothly
        binding.errorContainer.animate()
            .alpha(0f)
            .setDuration(400L)
            .withEndAction {
                binding.errorContainer.visibility = View.GONE
                binding.errorContainer.alpha = 1f
                binding.gameBottomArea.visibility = View.GONE
            }
            .start()
    }

    private fun showErrorWithGame(message: String, isNetworkError: Boolean = false) {
        binding.errorText.text = message
        binding.errorText.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.error_overlay_text_subdued)
        )
        // Only show the "No Internet Connectivity" banner for actual network failures
        binding.errorSignalMessage.visibility = if (isNetworkError) View.VISIBLE else View.GONE
        binding.octopusErrorGame.visibility = View.VISIBLE
        binding.gameBottomArea.visibility = View.VISIBLE
        binding.errorContainer.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.error_overlay_background)
        )
        // Cancel any in-flight hide-animation so its end-action can't hide the overlay
        // after we have just re-shown it.
        binding.errorContainer.animate().cancel()
        binding.errorContainer.alpha = 1f
        binding.errorContainer.visibility = View.VISIBLE
        // Sessions RecyclerView stays visible behind the dim overlay when sessions exist
        binding.sessionsRecyclerView.visibility = View.VISIBLE

        val gameView = binding.octopusErrorGame
        gameView.highScore = PreferenceUtils.getOctopusHighScore(this)
        gameView.onGameOver = {
            val hs = gameView.highScore
            if (hs > PreferenceUtils.getOctopusHighScore(this)) {
                PreferenceUtils.setOctopusHighScore(this, hs)
            }
        }

        // The entire area below the game: start/jump/restart depending on game state
        binding.gameBottomArea.setOnClickListener {
            when {
                gameView.isWaitingToPlay -> gameView.startGame()
                gameView.isGameOver -> gameView.startGame()
                else -> gameView.jump()
            }
        }

        gameView.prepareToPlay()
    }

    private fun startSkeletonShimmer() {
        val animators = mutableListOf<ObjectAnimator>()
        val layout = binding.skeletonLayout
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            val animator = ObjectAnimator.ofFloat(child, View.ALPHA, 0.4f, 1.0f).apply {
                duration = 900L
                startDelay = (i * 130L)
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
            animator.start()
            animators.add(animator)
        }
        shimmerAnimators = animators
    }

    private fun stopSkeletonShimmer() {
        shimmerAnimators.forEach { it.cancel() }
        shimmerAnimators = emptyList()
    }

    private fun loadMoreSessions() {
        if (isLoadingMore || nextPageToken == null) return
        isLoadingMore = true
        adapter.setLoading(true)

        lifecycleScope.launch {
            try {
                val response = repository.getSessions(pageToken = nextPageToken)
                val newSessions = response.sessions ?: emptyList()
                nextPageToken = response.nextPageToken

                allSessions = allSessions + newSessions
                applyFilters()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading more sessions", e)
            } finally {
                isLoadingMore = false
                adapter.setLoading(false)
            }
        }
    }
}

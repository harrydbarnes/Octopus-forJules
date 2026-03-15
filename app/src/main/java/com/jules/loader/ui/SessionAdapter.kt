package com.jules.loader.ui

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.jules.loader.R
import com.jules.loader.data.model.Session
import com.jules.loader.util.DateUtils
import com.jules.loader.util.PreferenceUtils

import android.content.Intent
import android.app.Activity
import android.os.Build
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.app.ActivityOptionsCompat

class SessionAdapter : ListAdapter<Session, RecyclerView.ViewHolder>(SessionDiffCallback()) {

    var isShortenRepoNamesEnabled: Boolean = true
    var isShortenDatesEnabled: Boolean = true
    var isDateFormatMMDD: Boolean = false
    private var isLoadingFooterVisible = false

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_LOADING = 1
        private const val SCROLL_DURATION_PER_PIXEL_MS = 12L  // ms per pixel of scrollable content
    }

    fun setLoading(isLoading: Boolean) {
        if (isLoadingFooterVisible == isLoading) return
        isLoadingFooterVisible = isLoading
        if (isLoading) {
            notifyItemInserted(currentList.size)
        } else {
            notifyItemRemoved(currentList.size)
        }
    }

    override fun getItemCount(): Int {
        return super.getItemCount() + if (isLoadingFooterVisible) 1 else 0
    }

    override fun getItemViewType(position: Int): Int {
        return if (isLoadingFooterVisible && position == itemCount - 1) {
            VIEW_TYPE_LOADING
        } else {
            VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_LOADING) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_loading, parent, false)
            LoadingViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
            SessionViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SessionViewHolder) {
            holder.bind(getItem(position), isShortenRepoNamesEnabled, isShortenDatesEnabled, isDateFormatMMDD)
        }
    }

    class LoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.sessionTitle)
        private val prompt: TextView = itemView.findViewById(R.id.sessionPrompt)
        private val sourceChip: Chip = itemView.findViewById(R.id.sourceChip)
        private val statusChip: Chip = itemView.findViewById(R.id.statusChip)
        private val dateChip: Chip = itemView.findViewById(R.id.dateChip)
        private val pulseView: View = itemView.findViewById(R.id.pulseView)
        private val badgeScrollView: HorizontalScrollView = itemView.findViewById(R.id.badgeScrollView)
        private var badgeScrollAnimator: ValueAnimator? = null

        fun bind(session: Session, shortenRepoNames: Boolean, shortenDates: Boolean = true, useMmDd: Boolean = false) {
            val context = itemView.context
            title.text = session.title ?: context.getString(R.string.untitled_session)
            prompt.text = session.prompt ?: context.getString(R.string.no_prompt)

            // Slightly smaller text for source/date badge chips
            sourceChip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            dateChip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)

            val statusRaw = session.status ?: "Idle"
            val status = statusRaw.replace("_", " ")
            statusChip.text = status

            // Set status color
            when(status) {
                TaskDetailActivity.STATUS_PR_OPEN -> statusChip.setChipBackgroundColorResource(R.color.status_pr_open)
                TaskDetailActivity.STATUS_EXECUTING_TESTS -> statusChip.setChipBackgroundColorResource(R.color.status_tests_passing)
                else -> statusChip.setChipBackgroundColorResource(R.color.jules_purple_light)
            }

            // Simple pulse animation (alpha)
            val animation = android.view.animation.AlphaAnimation(0.4f, 1.0f)
            animation.duration = if (status == "Idle") 0 else 1000 // Only pulse if active
            animation.repeatCount = if (status == "Idle") 0 else android.view.animation.Animation.INFINITE
            animation.repeatMode = android.view.animation.Animation.REVERSE
            pulseView.startAnimation(animation)

            val source = session.sourceContext?.source ?: context.getString(R.string.unknown_source)
            // Clean up source string (e.g. "sources/github/user/repo" -> "user/repo")
            val cleanSource = if (source.startsWith("sources/github/")) {
                source.removePrefix("sources/github/")
            } else {
                source
            }
            sourceChip.text = PreferenceUtils.getDisplayRepoName(cleanSource, shortenRepoNames)

            // Set date
            val formattedDate = if (shortenDates) {
                DateUtils.formatDateShort(session.createTime, useMmDd)
            } else {
                DateUtils.formatDate(session.createTime)
            }
            if (formattedDate != null) {
                dateChip.text = formattedDate
                dateChip.visibility = View.VISIBLE
            } else {
                dateChip.visibility = View.GONE
            }

            // Reset and re-evaluate scroll animation for source+date chips
            badgeScrollAnimator?.cancel()
            badgeScrollAnimator = null
            badgeScrollView.scrollX = 0

            // Token to ensure animation only starts for the current binding
            val sessionToken = session.id
            badgeScrollView.tag = sessionToken
            badgeScrollView.post {
                if (badgeScrollView.tag != sessionToken) return@post
                val inner = badgeScrollView.getChildAt(0) ?: return@post
                val maxScroll = inner.width - badgeScrollView.width
                if (maxScroll > 0) {
                    badgeScrollAnimator = ValueAnimator.ofInt(0, maxScroll, 0).apply {
                        duration = (1500L + maxScroll * SCROLL_DURATION_PER_PIXEL_MS).coerceAtMost(5000L)
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        interpolator = AccelerateDecelerateInterpolator()
                        startDelay = 800L
                        addUpdateListener { badgeScrollView.scrollX = it.animatedValue as Int }
                    }
                    badgeScrollAnimator?.start()
                }
            }

            itemView.transitionName = "shared_element_container_${session.id}"

            itemView.setOnClickListener {
                val intent = Intent(context, TaskDetailActivity::class.java).apply {
                    putExtra(TaskDetailActivity.EXTRA_SESSION_ID, session.id)
                    putExtra(TaskDetailActivity.EXTRA_SESSION_TITLE, session.title)
                    putExtra(TaskDetailActivity.EXTRA_SESSION_PROMPT, session.prompt)
                    putExtra(TaskDetailActivity.EXTRA_SESSION_STATUS, session.status)
                    putExtra(TaskDetailActivity.EXTRA_SESSION_SOURCE, session.sourceContext?.source)
                    putExtra(TaskDetailActivity.EXTRA_SESSION_BRANCH, session.sourceContext?.githubRepoContext?.startingBranch)
                }

                val activity = context as? Activity
                if (activity != null) {
                    val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        itemView,
                        "shared_element_container_${session.id}"
                    )
                    // Clear any RenderEffect blur from the RecyclerView before the
                    // shared-element transition capture. MaterialContainerTransform
                    // captures the source view synchronously inside startActivity() —
                    // before onPause() — so blur must be cleared here, not in onPause().
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        (itemView.parent as? RecyclerView)?.setRenderEffect(null)
                    }
                    context.startActivity(intent, options.toBundle())
                } else {
                    context.startActivity(intent)
                }
            }
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<Session>() {
        override fun areItemsTheSame(oldItem: Session, newItem: Session): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Session, newItem: Session): Boolean {
            return oldItem == newItem
        }
    }
}

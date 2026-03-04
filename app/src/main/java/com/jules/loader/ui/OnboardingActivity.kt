package com.jules.loader.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jules.loader.MainActivity
import com.jules.loader.R
import com.jules.loader.data.JulesRepository
import com.jules.loader.databinding.ActivityOnboardingBinding
import com.jules.loader.util.ThemeUtils

class OnboardingActivity : BaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var detectedApiKey: String? = null
    private val repository: JulesRepository by lazy { JulesRepository.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = OnboardingAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { _, _ -> }.attach()

        if (savedInstanceState != null) {
            val position = savedInstanceState.getInt("current_page", 0)
            binding.viewPager.setCurrentItem(position, false)
        } else {
            val startPage = intent.getIntExtra("start_page", 0)
            if (startPage > 0) {
                binding.viewPager.setCurrentItem(startPage, false)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("current_page", binding.viewPager.currentItem)
    }

    override fun onResume() {
        super.onResume()
        checkClipboard()
        (binding.imageViewAnimation.drawable as? Animatable)?.start()
    }

    override fun onPause() {
        super.onPause()
        (binding.imageViewAnimation.drawable as? Animatable)?.stop()
    }

    private fun checkClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                val item = clipboard.primaryClip?.getItemAt(0)
                val text = item?.text?.toString()

                // Heuristic for Jules API Key (Google API Key starts with AIza)
                if (!text.isNullOrEmpty() && text.startsWith("AIza") && text.length > 20) {
                     detectedApiKey = text
                     val lastPage = (binding.viewPager.adapter?.itemCount ?: 1) - 1
                     // Notify adapter to update view if visible, or it will be picked up on bind
                     binding.viewPager.adapter?.notifyItemChanged(lastPage)

                     // Scroll to last page if found
                     binding.viewPager.currentItem = lastPage
                }
            }
        } catch (e: Exception) {
            // Ignore clipboard errors
        }
    }

    private class OnboardingAdapter(private val activity: OnboardingActivity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val VIEW_TYPE_WELCOME = 0
            private const val VIEW_TYPE_THEME = 1
            private const val VIEW_TYPE_API = 2
        }

        override fun getItemViewType(position: Int): Int {
            return when (position) {
                0 -> VIEW_TYPE_WELCOME
                1 -> VIEW_TYPE_THEME
                else -> VIEW_TYPE_API
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_API -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_api, parent, false)
                    ApiViewHolder(view)
                }
                VIEW_TYPE_THEME -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_theme, parent, false)
                    ThemeViewHolder(view)
                }
                else -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_welcome, parent, false)
                    WelcomeViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is WelcomeViewHolder) {
                holder.bind()
            } else if (holder is ApiViewHolder) {
                holder.bind()
            } else if (holder is ThemeViewHolder) {
                holder.bind()
            }
        }

        override fun getItemCount(): Int = 3

        inner class WelcomeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nextButton: Button = itemView.findViewById(R.id.nextButton)

            fun bind() {
                nextButton.setOnClickListener {
                    activity.binding.viewPager.currentItem = activity.binding.viewPager.currentItem + 1
                }
            }
        }

        inner class ThemeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val radioGroup: RadioGroup = itemView.findViewById(R.id.themeRadioGroup)
            private val nextButton: Button = itemView.findViewById(R.id.nextButton)

            fun bind() {
                val currentTheme = ThemeUtils.getSelectedTheme(activity)
                radioGroup.setOnCheckedChangeListener(null)
                when (currentTheme) {
                    ThemeUtils.THEME_SQUID -> radioGroup.check(R.id.radioSquid)
                    ThemeUtils.THEME_CUTTLEFISH -> radioGroup.check(R.id.radioCuttlefish)
                    else -> radioGroup.check(R.id.radioOctopus)
                }

                radioGroup.setOnCheckedChangeListener { _, checkedId ->
                    val newTheme = when (checkedId) {
                        R.id.radioSquid -> ThemeUtils.THEME_SQUID
                        R.id.radioCuttlefish -> ThemeUtils.THEME_CUTTLEFISH
                        else -> ThemeUtils.THEME_OCTOPUS
                    }
                    if (newTheme != ThemeUtils.getSelectedTheme(activity)) {
                        ThemeUtils.setSelectedTheme(activity, newTheme)
                        activity.recreate()
                    }
                }

                nextButton.setOnClickListener {
                    activity.binding.viewPager.currentItem = activity.binding.viewPager.currentItem + 1
                }
            }
        }

        inner class ApiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val inputLayout: TextInputLayout = itemView.findViewById(R.id.apiKeyInputLayout)
            private val input: TextInputEditText = itemView.findViewById(R.id.apiKeyInput)
            private val getKeyLink: TextView = itemView.findViewById(R.id.getKeyLink)
            private val saveButton: Button = itemView.findViewById(R.id.saveButton)
            private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)

            private fun setLoading(isLoading: Boolean) {
                progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                saveButton.visibility = if (isLoading) View.INVISIBLE else View.VISIBLE
                input.isEnabled = !isLoading
            }

            fun bind() {
                if (activity.detectedApiKey != null) {
                    input.setText(activity.detectedApiKey)
                }

                inputLayout.setEndIconOnClickListener {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    try {
                        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                            val item = clipboard.primaryClip?.getItemAt(0)
                            val text = item?.text?.toString()
                            if (!text.isNullOrEmpty()) {
                                input.setText(text)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore clipboard errors
                    }
                }

                getKeyLink.setOnClickListener {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://jules.google.com/settings/api"))
                    activity.startActivity(browserIntent)
                }

                input.setOnEditorActionListener { _, actionId, event ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                        saveButton.performClick()
                        true
                    } else {
                        false
                    }
                }

                saveButton.setOnClickListener {
                    val key = input.text?.toString()?.trim()
                    if (key.isNullOrEmpty()) {
                        Toast.makeText(activity, R.string.error_enter_api_key, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    if (key.length != JulesRepository.API_KEY_LENGTH) {
                        Toast.makeText(activity, R.string.error_api_key_length, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // Validate
                    setLoading(true)

                    activity.lifecycleScope.launchWhenStarted {
                        val isValid = activity.repository.validateApiKey(key)
                        if (isValid) {
                            activity.repository.saveApiKey(key)
                            activity.startActivity(Intent(activity, MainActivity::class.java))
                            activity.finish()
                        } else {
                            setLoading(false)
                            Toast.makeText(activity, R.string.error_api_key_invalid, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

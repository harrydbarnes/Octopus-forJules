package com.jules.loader.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jules.loader.R
import com.jules.loader.data.JulesRepository
import com.jules.loader.databinding.ActivitySettingsBinding
import com.jules.loader.util.PreferenceUtils
import com.jules.loader.util.ThemeUtils

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repository: JulesRepository by lazy { JulesRepository.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.menu_settings)

        setupThemeSelection()
        setupDisplaySettings()
        setupApiKeySection()
    }

    private fun setupApiKeySection() {
        val apiKey = repository.getApiKey()
        if (apiKey.isNullOrEmpty()) {
            binding.cardApiKey.visibility = View.GONE
        } else {
            binding.cardApiKey.visibility = View.VISIBLE
            binding.btnEditApiKey.setOnClickListener {
                showEditApiKeyDialog()
            }
            binding.btnRemoveApiKey.setOnClickListener {
                showRemoveApiKeyDialog()
            }
        }
    }

    private fun showEditApiKeyDialog() {
        val layout = layoutInflater.inflate(R.layout.dialog_edit_api_key, null)
        val inputLayout = layout.findViewById<TextInputLayout>(R.id.apiKeyInputLayout)
        val input = layout.findViewById<TextInputEditText>(R.id.apiKeyInput)
        val progressBar = layout.findViewById<ProgressBar>(R.id.progressBar)

        val apiKey = repository.getApiKey()
        if (!apiKey.isNullOrEmpty()) {
            input.setText(apiKey)
        }

        inputLayout.setEndIconOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_edit_api_key)
            .setView(layout)
            .setPositiveButton(R.string.action_save, null) // Listener set later to prevent auto-dismiss
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                // Smoothly hide keyboard on cancel
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
            }
            .create()

        // Configure dialog window to sit at the top of the screen to prevent keyboard jump
        dialog.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.gravity = android.view.Gravity.TOP
            layoutParams.y = resources.getDimensionPixelSize(R.dimen.dialog_top_margin) // Top margin
            window.attributes = layoutParams
        }

        dialog.show()

        // Focus input and show keyboard automatically for better UX
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Hide keyboard on save attempt
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)

            val newKey = input.text?.toString()?.trim()
            if (newKey.isNullOrEmpty()) {
                Toast.makeText(this, getString(R.string.error_enter_api_key), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newKey.length != JulesRepository.API_KEY_LENGTH) {
                Toast.makeText(this, getString(R.string.error_api_key_length), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            progressBar.visibility = View.VISIBLE
            input.isEnabled = false

            lifecycleScope.launchWhenStarted {
                val isValid = repository.validateApiKey(newKey)
                if (isValid) {
                    repository.saveApiKey(newKey)
                    setupApiKeySection()
                    Toast.makeText(this@SettingsActivity, getString(R.string.message_api_key_updated), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    progressBar.visibility = View.GONE
                    input.isEnabled = true
                    Toast.makeText(this@SettingsActivity, getString(R.string.error_api_key_invalid), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRemoveApiKeyDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_confirm_remove_title)
            .setMessage(R.string.dialog_confirm_remove_message)
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                repository.clearApiKey()
                val intent = Intent(this, OnboardingActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                intent.putExtra("start_page", 2)
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun setupDisplaySettings() {
        binding.switchShortenRepoNames.isChecked = PreferenceUtils.isShortenRepoNamesEnabled(this)
        binding.switchShortenRepoNames.setOnCheckedChangeListener { _, isChecked ->
            PreferenceUtils.setShortenRepoNamesEnabled(this, isChecked)
        }

        binding.switchVoiceTyping.isChecked = PreferenceUtils.isVoiceTypingEnabled(this)
        binding.switchVoiceTyping.setOnCheckedChangeListener { _, isChecked ->
            PreferenceUtils.setVoiceTypingEnabled(this, isChecked)
        }

        binding.switchPromptGallery.isChecked = PreferenceUtils.isPromptGalleryEnabled(this)
        binding.switchPromptGallery.setOnCheckedChangeListener { _, isChecked ->
            PreferenceUtils.setPromptGalleryEnabled(this, isChecked)
        }
    }

    private fun setupThemeSelection() {
        val currentTheme = ThemeUtils.getSelectedTheme(this)
        when (currentTheme) {
            ThemeUtils.THEME_SQUID -> binding.radioSquid.isChecked = true
            ThemeUtils.THEME_CUTTLEFISH -> binding.radioCuttlefish.isChecked = true
            else -> binding.radioOctopus.isChecked = true
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val newTheme = when (checkedId) {
                R.id.radio_squid -> ThemeUtils.THEME_SQUID
                R.id.radio_cuttlefish -> ThemeUtils.THEME_CUTTLEFISH
                else -> ThemeUtils.THEME_OCTOPUS
            }
            // Only recreate if changed to avoid loop (though RadioGroup listener usually fires only on change)
            if (newTheme != ThemeUtils.getSelectedTheme(this)) {
                ThemeUtils.setSelectedTheme(this, newTheme)
                recreate()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

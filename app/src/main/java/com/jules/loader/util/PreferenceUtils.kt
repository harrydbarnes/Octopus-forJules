package com.jules.loader.util

import android.content.Context
import androidx.core.content.edit

object PreferenceUtils {
    private const val PREFS_NAME = "jules_settings"
    private const val KEY_SHORTEN_REPO_NAMES = "shorten_repo_names"
    private const val KEY_VOICE_TYPING = "voice_typing"
    private const val KEY_PROMPT_GALLERY = "prompt_gallery"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isShortenRepoNamesEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHORTEN_REPO_NAMES, true)
    }

    fun isVoiceTypingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VOICE_TYPING, true)
    }

    fun isPromptGalleryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROMPT_GALLERY, true)
    }

    fun getDisplayRepoName(context: Context, fullName: String): String {
        return getDisplayRepoName(fullName, isShortenRepoNamesEnabled(context))
    }

    fun getDisplayRepoName(fullName: String, shorten: Boolean): String {
        return if (shorten) {
            fullName.substringAfterLast('/')
        } else {
            fullName
        }
    }

    fun setShortenRepoNamesEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_SHORTEN_REPO_NAMES, enabled) }
    }

    fun setVoiceTypingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_VOICE_TYPING, enabled) }
    }

    fun setPromptGalleryEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_PROMPT_GALLERY, enabled) }
    }
}

package com.jules.loader.ui

data class PromptItem(
    val id: String,
    val title: String,
    val isCustom: Boolean,
    val body: String = "",
    val isEnabled: Boolean = true,
    val originalBody: String? = null,
    val originalTitle: String? = null
)

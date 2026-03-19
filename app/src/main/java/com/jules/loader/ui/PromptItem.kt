package com.jules.loader.ui

data class PromptItem(
    val id: String,
    val title: String,
    val isCustom: Boolean,
    val body: String = "",
    val isEnabled: Boolean = true
)

package com.jules.loader.ui

import android.content.Context
import com.jules.loader.R

/**
 * Single source of truth for the built-in (default) prompt definitions.
 * Both [CreateTaskActivity] and [ManagePromptsActivity] call [getDefaultPrompts]
 * so the two screens always stay in sync.
 */
object DefaultPrompts {

    fun getDefaultPrompts(context: Context): List<PromptItem> = listOf(
        PromptItem("performance", context.getString(R.string.prompt_performance_title), false, "performance.md", true, "performance.md"),
        PromptItem("design", context.getString(R.string.prompt_design_title), false, "design.md", true, "design.md"),
        PromptItem("security", context.getString(R.string.prompt_security_title), false, "security.md", true, "security.md"),
        PromptItem("bug_hunt", context.getString(R.string.prompt_bug_hunt_title), false, "bug_hunt.md", true, "bug_hunt.md"),
        PromptItem("dependencies", context.getString(R.string.prompt_update_dependencies_title), false, "update_dependencies.md", true, "update_dependencies.md"),
        PromptItem("readme", context.getString(R.string.prompt_readme_title), false, "readme.md", true, "readme.md"),
        PromptItem("simplify", context.getString(R.string.prompt_simplify_title), false, "simplify.md", true, "simplify.md"),
        PromptItem("refactor", context.getString(R.string.prompt_refactor_title), false, "refactor.md", true, "refactor.md"),
        PromptItem("unit_tests", context.getString(R.string.prompt_unit_tests_title), false, "unit_tests.md", true, "unit_tests.md"),
        PromptItem("janitor", context.getString(R.string.prompt_janitor_title), false, "janitor.md", true, "janitor.md"),
        PromptItem("accessibility", context.getString(R.string.prompt_accessibility_title), false, "accessibility.md", true, "accessibility.md")
    )
}

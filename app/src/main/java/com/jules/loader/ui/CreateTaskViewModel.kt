package com.jules.loader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jules.loader.R
import com.jules.loader.data.JulesRepository
import com.jules.loader.data.model.SourceContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CreateTaskViewModel(private val repository: JulesRepository) : ViewModel() {

    private val _availableSources = MutableStateFlow<List<SourceContext>>(emptyList())
    val availableSources: StateFlow<List<SourceContext>> = _availableSources

    private val _errorEvent = MutableSharedFlow<Int>()
    val errorEvent: SharedFlow<Int> = _errorEvent

    private val _taskCreatedEvent = MutableSharedFlow<Unit>()
    val taskCreatedEvent: SharedFlow<Unit> = _taskCreatedEvent

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isSourcesLoading = MutableStateFlow(false)
    val isSourcesLoading: StateFlow<Boolean> = _isSourcesLoading

    private val _availableBranches = MutableStateFlow<List<String>>(emptyList())
    val availableBranches: StateFlow<List<String>> = _availableBranches

    private val _isBranchesLoading = MutableStateFlow(false)
    val isBranchesLoading: StateFlow<Boolean> = _isBranchesLoading

    private val _selectedBranch = MutableStateFlow<String?>(null)
    val selectedBranch: StateFlow<String?> = _selectedBranch

    companion object {
        private val TAG = CreateTaskViewModel::class.java.simpleName
    }

    init {
        loadSources()
    }

    private fun loadSources() {
        viewModelScope.launch {
            if (!repository.hasValidSourceCache()) {
                _isSourcesLoading.value = true
            }
            try {
                // Fetch first page, handle further pages locally if needed.
                val response = repository.getSources()
                _availableSources.value = response.sources ?: emptyList()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load repositories", e)
                _errorEvent.emit(R.string.error_load_repositories)
            } finally {
                _isSourcesLoading.value = false
            }
        }
    }

    fun submitTask(
        prompt: String,
        repoUrl: String?,
        branch: String?,
        automationMode: String? = null,
        requirePlanApproval: Boolean? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.createSession(prompt, repoUrl, branch, automationMode, requirePlanApproval)
                _taskCreatedEvent.emit(Unit)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error creating session", e)
                _errorEvent.emit(R.string.error_create_task)
            } finally {
                _isLoading.value = false
            }
        }
    }
    fun onSourceSelected(sourceName: String) {
        viewModelScope.launch {
            _isBranchesLoading.value = true
            try {
                val fullSource = repository.getSource(sourceName)
                val branches = fullSource.githubRepoContext?.branches?.map { it.displayName } ?: emptyList()
                _availableBranches.value = branches

                val defaultBranch = fullSource.githubRepoContext?.defaultBranch?.displayName
                if (defaultBranch != null && branches.contains(defaultBranch)) {
                    _selectedBranch.value = defaultBranch
                } else if (branches.isNotEmpty()) {
                    _selectedBranch.value = branches.first()
                } else {
                    _selectedBranch.value = null
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load branches for source", e)
                _errorEvent.emit(R.string.error_load_branches)
                _availableBranches.value = emptyList()
                _selectedBranch.value = null
            } finally {
                _isBranchesLoading.value = false
            }
        }
    }

    fun onBranchSelected(branchName: String) {
        _selectedBranch.value = branchName
    }
}

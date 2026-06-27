package com.kaiser.aiagent.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.logging.LogRepository
import com.kaiser.aiagent.data.remote.RemoteConfigRepository
import com.kaiser.aiagent.data.updater.UpdateCheckResult
import com.kaiser.aiagent.data.updater.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State container for the Home screen. At v0.1 this is mostly a thin
 * wrapper around the update checker; future versions will surface agent
 * status, conversation shortcuts, etc.
 *
 * The VM deliberately exposes a single [UiState] snapshot rather than
 * multiple flows — the screen is simple enough that this keeps
 * recomposition cheap and the code readable.
 */
class HomeViewModel(
    private val updateRepository: UpdateRepository,
    private val remoteConfigRepository: RemoteConfigRepository
) : ViewModel() {

    data class UiState(
        val installedVersion: String = "",
        val latestVersion: String? = null,
        val checking: Boolean = false,
        val updateAvailable: Boolean = false,
        val updateAssetUrl: String? = null,
        val releaseNotes: String? = null,
        val toast: String? = null,
        val agentDisabled: Boolean = false,
        val maintenanceMode: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Seed installed version + cached remote config so the UI renders
        // something useful immediately, even offline.
        viewModelScope.launch {
            remoteConfigRepository.cachedConfigFlow.collectLatest { cfg ->
                _state.value = _state.value.copy(
                    agentDisabled = cfg.disableAgent,
                    maintenanceMode = cfg.maintenanceMode,
                    latestVersion = cfg.latestVersion
                )
            }
        }
    }

    fun setInstalledVersion(version: String) {
        _state.value = _state.value.copy(installedVersion = version)
    }

    /** Triggers a GitHub release check. Updates state and emits a toast. */
    fun checkForUpdate() {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, toast = null)
        viewModelScope.launch {
            when (val result = updateRepository.checkForUpdate()) {
                is UpdateCheckResult.UpToDate -> {
                    _state.value = _state.value.copy(
                        checking = false,
                        updateAvailable = false,
                        toast = "You are on the latest version."
                    )
                }
                is UpdateCheckResult.Available -> {
                    _state.value = _state.value.copy(
                        checking = false,
                        updateAvailable = true,
                        updateAssetUrl = result.assetUrl,
                        latestVersion = result.latest.tagName.removePrefix("v"),
                        releaseNotes = result.latest.body ?: result.latest.name
                    )
                }
                is UpdateCheckResult.Failed -> {
                    _state.value = _state.value.copy(
                        checking = false,
                        toast = "Update check failed: ${result.message}"
                    )
                    Timber.w("Update check failed: %s", result.message)
                }
            }
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }
}

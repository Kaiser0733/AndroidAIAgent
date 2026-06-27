package com.kaiser.aiagent.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.remote.RemoteConfigRepository
import com.kaiser.aiagent.data.updater.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Settings screen. Exposes the configurable URLs (remote config
 * + updater repo) and persists changes back to DataStore via the
 * repositories.
 */
class SettingsViewModel(
    private val context: Context,
    private val updateRepository: UpdateRepository
) : ViewModel() {

    // We need this to read/write the remote config URL.
    private val remoteConfigRepository = RemoteConfigRepository(context)

    data class UiState(
        val remoteConfigUrl: String = "",
        val updaterRepo: String = "",
        val autoCheckUpdates: Boolean = true,
        val dirty: Boolean = false
    )

    private val _draftUrl = MutableStateFlow<String?>(null)
    private val _draftRepo = MutableStateFlow<String?>(null)
    private val _autoCheck = MutableStateFlow(true)

    val state: StateFlow<UiState> = combine(
        remoteConfigRepository.configUrlFlow,
        updateRepository.repoFlow,
        _draftUrl,
        _draftRepo,
        _autoCheck
    ) { savedUrl, savedRepo, draftUrl, draftRepo, autoCheck ->
        val url = draftUrl ?: savedUrl
        val repo = draftRepo ?: savedRepo
        UiState(
            remoteConfigUrl = url,
            updaterRepo = repo,
            autoCheckUpdates = autoCheck,
            dirty = draftUrl != null || draftRepo != null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun updateRemoteConfigUrl(value: String) {
        _draftUrl.value = value
    }

    fun updateUpdaterRepo(value: String) {
        _draftRepo.value = value
    }

    fun setAutoCheckUpdates(value: Boolean) {
        _autoCheck.value = value
    }

    fun save() {
        viewModelScope.launch {
            _draftUrl.value?.let { remoteConfigRepository.setConfigUrl(it) }
            _draftRepo.value?.let { updateRepository.setRepo(it) }
            _draftUrl.value = null
            _draftRepo.value = null
        }
    }
}

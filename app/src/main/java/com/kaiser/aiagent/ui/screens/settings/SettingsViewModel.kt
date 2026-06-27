package com.kaiser.aiagent.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.ai.AiConfig
import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.remote.RemoteConfigRepository
import com.kaiser.aiagent.data.updater.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Settings screen. v0.3 adds three new sections compared to
 * v0.2:
 *  - AI configuration (API key, endpoint, model, temperature, max tokens)
 *  - Test connection button
 *  - Debug screen entry (handled by the screen, not the VM)
 */
class SettingsViewModel(
    private val context: Context,
    private val updateRepository: UpdateRepository,
    private val aiRepository: AiRepository
) : ViewModel() {

    private val remoteConfigRepository = RemoteConfigRepository(context)

    data class UiState(
        // Updater / remote config (existing)
        val remoteConfigUrl: String = "",
        val updaterRepo: String = "",
        val autoCheckUpdates: Boolean = true,
        // AI (new in v0.3)
        val apiKey: String = "",
        val endpoint: String = "",
        val model: String = "",
        val temperature: Double = 0.7,
        val topP: Double = -1.0,
        val maxTokens: Int = -1,
        val extraBody: String = "",
        val testingConnection: Boolean = false,
        val connectionStatus: String? = null,
        // Common
        val dirty: Boolean = false
    )

    private val _draftUrl = MutableStateFlow<String?>(null)
    private val _draftRepo = MutableStateFlow<String?>(null)
    private val _autoCheck = MutableStateFlow(true)
    private val _draftApiKey = MutableStateFlow<String?>(null)
    private val _draftEndpoint = MutableStateFlow<String?>(null)
    private val _draftModel = MutableStateFlow<String?>(null)
    private val _draftTemp = MutableStateFlow<Double?>(null)
    private val _draftMaxTokens = MutableStateFlow<Int?>(null)
    private val _draftTopP = MutableStateFlow<Double?>(null)
    private val _draftExtraBody = MutableStateFlow<String?>(null)
    private val _testing = MutableStateFlow(false)
    private val _connStatus = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        remoteConfigRepository.configUrlFlow,
        updateRepository.repoFlow,
        aiRepository.configFlow,
        _draftUrl, _draftRepo, _autoCheck,
        _draftApiKey, _draftEndpoint, _draftModel, _draftTemp, _draftMaxTokens,
        _draftTopP, _draftExtraBody,
        _testing, _connStatus
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val savedUrl = values[0] as String
        @Suppress("UNCHECKED_CAST")
        val savedRepo = values[1] as String
        val aiCfg = values[2] as AiConfig
        val dUrl = values[3] as String?
        val dRepo = values[4] as String?
        val autoCheck = values[5] as Boolean
        val dApiKey = values[6] as String?
        val dEndpoint = values[7] as String?
        val dModel = values[8] as String?
        val dTemp = values[9] as Double?
        val dMax = values[10] as Int?
        val dTopP = values[11] as Double?
        val dExtraBody = values[12] as String?
        val testing = values[13] as Boolean
        val connStatus = values[14] as String?

        val url = dUrl ?: savedUrl
        val repo = dRepo ?: savedRepo
        val apiKey = dApiKey ?: aiCfg.apiKey
        val endpoint = dEndpoint ?: aiCfg.endpoint
        val model = dModel ?: aiCfg.model
        val temp = dTemp ?: aiCfg.temperature
        val topP = dTopP ?: (aiCfg.topP ?: -1.0)
        val maxTok = dMax ?: (aiCfg.maxTokens ?: -1)
        val extraBody = dExtraBody ?: aiCfg.extraBody

        UiState(
            remoteConfigUrl = url,
            updaterRepo = repo,
            autoCheckUpdates = autoCheck,
            apiKey = apiKey,
            endpoint = endpoint,
            model = model,
            temperature = temp,
            topP = topP,
            maxTokens = maxTok,
            extraBody = extraBody,
            testingConnection = testing,
            connectionStatus = connStatus,
            dirty = listOf(dUrl, dRepo, dApiKey, dEndpoint, dModel, dTemp, dMax, dTopP, dExtraBody).any { it != null }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    // ---- Updater / remote config setters (existing) --------------------
    fun updateRemoteConfigUrl(value: String) { _draftUrl.value = value }
    fun updateUpdaterRepo(value: String) { _draftRepo.value = value }
    fun setAutoCheckUpdates(value: Boolean) { _autoCheck.value = value }

    // ---- AI setters (new in v0.3) -------------------------------------
    fun updateApiKey(value: String) { _draftApiKey.value = value }
    fun updateEndpoint(value: String) { _draftEndpoint.value = value }
    fun updateModel(value: String) { _draftModel.value = value }
    fun updateTemperature(value: Double) { _draftTemp.value = value }
    fun updateMaxTokens(value: Int) { _draftMaxTokens.value = value }
    fun updateTopP(value: Double) { _draftTopP.value = value }
    fun updateExtraBody(value: String) { _draftExtraBody.value = value }

    /**
     * Saves all dirty fields to their respective DataStores. AI config
     * is written via [AiRepository.updateConfig] so the AiService picks
     * up the change immediately.
     */
    fun save() {
        viewModelScope.launch {
            _draftUrl.value?.let { remoteConfigRepository.setConfigUrl(it) }
            _draftRepo.value?.let { updateRepository.setRepo(it) }
            // Only write AI config if any AI field was drafted.
            if (_draftApiKey.value != null || _draftEndpoint.value != null ||
                _draftModel.value != null || _draftTemp.value != null ||
                _draftMaxTokens.value != null || _draftTopP.value != null ||
                _draftExtraBody.value != null
            ) {
                aiRepository.updateConfig { current ->
                    current.copy(
                        apiKey = _draftApiKey.value ?: current.apiKey,
                        endpoint = _draftEndpoint.value ?: current.endpoint,
                        model = _draftModel.value ?: current.model,
                        temperature = _draftTemp.value ?: current.temperature,
                        topP = _draftTopP.value?.takeIf { it > 0 } ?: current.topP,
                        maxTokens = _draftMaxTokens.value?.takeIf { it > 0 }
                            ?: current.maxTokens,
                        extraBody = _draftExtraBody.value ?: current.extraBody
                    )
                }
            }
            // Clear drafts
            _draftUrl.value = null
            _draftRepo.value = null
            _draftApiKey.value = null
            _draftEndpoint.value = null
            _draftModel.value = null
            _draftTemp.value = null
            _draftMaxTokens.value = null
            _draftTopP.value = null
            _draftExtraBody.value = null
        }
    }

    /**
     * Saves the AI config first (so the test uses the latest values),
     * then runs [AiRepository.testConnection]. The result is written
     * to [UiState.connectionStatus].
     */
    fun testConnection() {
        if (_testing.value) return
        viewModelScope.launch {
            // Save AI drafts first so the test uses the new values.
            if (_draftApiKey.value != null || _draftEndpoint.value != null ||
                _draftModel.value != null
            ) {
                aiRepository.updateConfig { current ->
                    current.copy(
                        apiKey = _draftApiKey.value ?: current.apiKey,
                        endpoint = _draftEndpoint.value ?: current.endpoint,
                        model = _draftModel.value ?: current.model
                    )
                }
                _draftApiKey.value = null
                _draftEndpoint.value = null
                _draftModel.value = null
                _draftTopP.value = null
                _draftExtraBody.value = null
            }
            _testing.value = true
            _connStatus.value = null
            val result = aiRepository.testConnection()
            _connStatus.value = result.fold(
                onSuccess = { "✓ $it" },
                onFailure = { "✗ ${it.message ?: it.javaClass.simpleName}" }
            )
            _testing.value = false
        }
    }
}

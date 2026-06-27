package com.kaiser.aiagent.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.ai.AiConfig
import com.kaiser.aiagent.domain.agent.AgentRuntime
import com.kaiser.aiagent.domain.tools.ToolRegistry
import com.kaiser.aiagent.memory.MemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Read-only view model for the Debug screen. Aggregates snapshots of:
 *  - Current AI config (endpoint, model, whether API key is set)
 *  - Agent runtime state (busy, last tool call, last error)
 *  - Registered tools (name + description)
 *  - Memory count
 *  - API status (lazy — only set after the user taps "Test Connection")
 */
class DebugViewModel(
    private val aiRepository: AiRepository,
    private val agentRuntime: AgentRuntime,
    private val toolRegistry: ToolRegistry,
    private val memoryManager: MemoryManager
) : ViewModel() {

    data class UiState(
        val endpoint: String = "",
        val model: String = "",
        val apiKeySet: Boolean = false,
        val agentBusy: Boolean = false,
        val lastToolCall: String? = null,
        val lastToolResultOk: Boolean? = null,
        val lastToolResultOutput: String? = null,
        val lastError: String? = null,
        val tools: List<ToolInfo> = emptyList(),
        val memoryCount: Int = 0,
        val apiStatus: String? = null,
        val testing: Boolean = false
    )

    data class ToolInfo(
        val name: String,
        val description: String,
        val argumentsSchema: String
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Watch AI config
        viewModelScope.launch {
            aiRepository.configFlow.collectLatest { cfg: AiConfig ->
                _state.value = _state.value.copy(
                    endpoint = cfg.endpoint,
                    model = cfg.model,
                    apiKeySet = cfg.apiKey.isNotBlank()
                )
            }
        }
        // Watch agent runtime state
        viewModelScope.launch {
            agentRuntime.state.collectLatest { s ->
                _state.value = _state.value.copy(
                    agentBusy = s.busy,
                    lastToolCall = s.lastToolCall,
                    lastToolResultOk = s.lastToolResult?.ok,
                    lastToolResultOutput = s.lastToolResult?.output?.take(120),
                    lastError = s.lastError
                )
            }
        }
        // Snapshot tools + memory count once
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(
            tools = toolRegistry.all().map {
                ToolInfo(it.name, it.description, it.argumentsSchema)
            },
            memoryCount = memoryManager.count()
        )
    }

    fun testConnection() {
        if (_state.value.testing) return
        _state.value = _state.value.copy(testing = true, apiStatus = null)
        viewModelScope.launch {
            val result = aiRepository.testConnection()
            _state.value = _state.value.copy(
                testing = false,
                apiStatus = result.fold(
                    onSuccess = { "OK: $it" },
                    onFailure = { "FAIL: ${it.message ?: it.javaClass.simpleName}" }
                )
            )
        }
    }
}

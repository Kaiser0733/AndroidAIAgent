package com.kaiser.aiagent.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.ai.AiConfig
import com.kaiser.aiagent.domain.agent.AgentRuntime
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolRegistry
import com.kaiser.aiagent.memory.MemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Read-only view model for the Debug screen. v0.4 adds:
 *  - Tool permission level per registered tool
 *  - File tool statistics (file tools called, last file operation)
 *  - Memory count (existing, now exposed via the search_memory tool too)
 *  - Tool counts by permission level (safe / confirmation / blocked)
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
        val lastToolResultSuccess: Boolean? = null,
        val lastToolResultData: String? = null,
        val lastToolResultError: String? = null,
        val lastError: String? = null,
        val tools: List<ToolInfo> = emptyList(),
        val toolStats: ToolStats = ToolStats(),
        val memoryCount: Int = 0,
        val apiStatus: String? = null,
        val testing: Boolean = false
    )

    data class ToolInfo(
        val name: String,
        val description: String,
        val argumentsSchema: String,
        val permissionLevel: ToolPermissionLevel
    )

    data class ToolStats(
        val total: Int = 0,
        val safe: Int = 0,
        val confirmationRequired: Int = 0,
        val blocked: Int = 0
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            aiRepository.configFlow.collectLatest { cfg: AiConfig ->
                _state.value = _state.value.copy(
                    endpoint = cfg.endpoint,
                    model = cfg.model,
                    apiKeySet = cfg.apiKey.isNotBlank()
                )
            }
        }
        viewModelScope.launch {
            agentRuntime.state.collectLatest { s ->
                _state.value = _state.value.copy(
                    agentBusy = s.busy,
                    lastToolCall = s.lastToolCall,
                    lastToolResultSuccess = s.lastToolResult?.success,
                    lastToolResultData = s.lastToolResult?.data?.take(120),
                    lastToolResultError = s.lastToolResult?.error,
                    lastError = s.lastError
                )
            }
        }
        refresh()
    }

    fun refresh() {
        val stats = toolRegistry.stats()
        _state.value = _state.value.copy(
            tools = toolRegistry.all().map {
                ToolInfo(it.name, it.description, it.argumentsSchema, it.permissionLevel)
            },
            toolStats = ToolStats(
                total = stats.total,
                safe = stats.safe,
                confirmationRequired = stats.confirmationRequired,
                blocked = stats.blocked
            ),
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

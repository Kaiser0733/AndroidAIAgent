package com.kaiser.aiagent.domain.agent

import com.kaiser.aiagent.data.ai.AiException
import com.kaiser.aiagent.data.ai.AiMessage
import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.ai.AiToolCall
import com.kaiser.aiagent.data.ai.AiToolCallFunction
import com.kaiser.aiagent.data.ai.StreamEvent
import com.kaiser.aiagent.data.logging.LogRepository
import com.kaiser.aiagent.domain.tools.ToolCall
import com.kaiser.aiagent.domain.tools.ToolExecutor
import com.kaiser.aiagent.domain.tools.ToolRegistry
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * v0.6: Complete rewrite for native function calling.
 * The model receives tool definitions via the `tools` API parameter
 * and returns structured `tool_calls` in the response.
 */
class AgentRuntime(
    private val repository: AiRepository,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val logRepository: LogRepository? = null
) {
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    suspend fun runTurn(
        history: List<AiMessage>,
        userMessage: String,
        conversationId: String? = null,
        onDelta: (String) -> Unit,
        onFinal: (String) -> Unit,
        onStatus: ((String) -> Unit)? = null
    ) {
        val systemPrompt = toolRegistry.describeForPrompt()
        val messages = mutableListOf<AiMessage>()
        messages.add(AiMessage(role = "system", content = systemPrompt))
        val truncatedHistory = if (history.size > 4) history.takeLast(4) else history
        messages.addAll(truncatedHistory)
        messages.add(AiMessage(role = "user", content = userMessage))

        _state.value = AgentState(busy = true, streamingText = "")

        try {
            val finalText = runLoop(messages, conversationId, onDelta, onStatus)
            _state.value = AgentState.IDLE
            onFinal(finalText)
        } catch (t: Throwable) {
            Timber.w(t, "Agent turn failed")
            _state.value = AgentState.IDLE.copy(lastError = t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    private suspend fun runLoop(
        messages: MutableList<AiMessage>,
        conversationId: String?,
        onDelta: (String) -> Unit,
        onStatus: ((String) -> Unit)? = null
    ): String {
        val toolDefs = toolRegistry.toJsonDefinitions()
        var iteration = 0
        val maxIterations = 5

        while (true) {
            if (iteration >= maxIterations) {
                Timber.w("Hit max iterations (%d)", maxIterations)
                return _state.value.streamingText.ifBlank {
                    "I reached the maximum number of tool calls for this turn."
                }
            }

            val textBuilder = StringBuilder()
            val toolCallAccumulator = mutableMapOf<Int, ToolCallAccum>()

            repository.streamChatWithTools(messages, toolDefs, onStatus).collect { event ->
                when (event) {
                    is StreamEvent.Text -> {
                        textBuilder.append(event.token)
                        _state.value = _state.value.copy(streamingText = textBuilder.toString())
                        onDelta(event.token)
                    }
                    is StreamEvent.ToolCallChunk -> {
                        val acc = toolCallAccumulator.getOrPut(event.index) { ToolCallAccum() }
                        if (event.id != null) acc.id = event.id
                        if (event.name != null) acc.name = event.name
                        acc.arguments.append(event.arguments)
                    }
                    is StreamEvent.Done -> {
                        Timber.i("Stream done: finishReason=%s", event.finishReason)
                    }
                    is StreamEvent.Error -> {
                        throw event.error
                    }
                }
            }

            val responseText = textBuilder.toString().trim()

            // Check if we got tool calls
            if (toolCallAccumulator.isNotEmpty()) {
                val toolCalls = toolCallAccumulator.toSortedMap().values.map { acc ->
                    AiToolCall(
                        id = acc.id,
                        function = AiToolCallFunction(name = acc.name, arguments = acc.arguments.toString())
                    )
                }

                // Add assistant message with tool_calls
                messages.add(AiMessage(
                    role = "assistant",
                    content = responseText.ifBlank { null },
                    toolCalls = toolCalls
                ))

                // Execute each tool call
                for (tc in toolCalls) {
                    onStatus?.invoke("Running: ${tc.function.name}")
                    val call = ToolCall(tool = tc.function.name, arguments = tc.function.arguments)
                    val result: ToolResult = try {
                        kotlinx.coroutines.withTimeout(30_000L) {
                            toolExecutor.execute(call, conversationId) { name, r ->
                                recordToolCall(name, r)
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        ToolResult(false, "", "Tool '${tc.function.name}' timed out after 30s.")
                    }

                    val truncatedData = if (result.data.length > 800)
                        result.data.take(800) + "..." else result.data

                    messages.add(AiMessage(
                        role = "tool",
                        content = truncatedData.ifBlank { result.error ?: "{}" },
                        toolCallId = tc.id,
                        name = tc.function.name
                    ))

                    logRepository?.appendUpdateLog(
                        "Tool ${tc.function.name}: ${if (result.success) "OK" else "FAIL"} ${truncatedData.take(100)}"
                    )
                }

                iteration++
                _state.value = _state.value.copy(streamingText = "")
                continue
            }

            // No tool calls — final text response
            return responseText
        }
    }

    private fun recordToolCall(toolName: String, result: ToolResult) {
        _state.value = _state.value.copy(
            lastToolCall = toolName,
            lastToolResult = result,
            toolCallsThisTurn = _state.value.toolCallsThisTurn + 1
        )
    }

    fun resetTurnCounters() { _state.value = AgentState.IDLE }

    private class ToolCallAccum {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()
    }
}

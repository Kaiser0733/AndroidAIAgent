package com.kaiser.aiagent.domain.agent

import com.kaiser.aiagent.data.ai.AiException
import com.kaiser.aiagent.data.ai.AiMessage
import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.domain.tools.ToolCall
import com.kaiser.aiagent.domain.tools.ToolCallParseResult
import com.kaiser.aiagent.domain.tools.ToolCallParser
import com.kaiser.aiagent.domain.tools.ToolExecutor
import com.kaiser.aiagent.domain.tools.ToolRegistry
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import timber.log.Timber

/**
 * Coordinates the multi-turn agent loop:
 *
 *   1. Build a context (system prompt + history + new user message).
 *   2. Stream the model's response.
 *   3. When the response completes, scan it for a tool-call request.
 *   4. If a tool call is found: execute it, append the tool result to
 *      the context as a tool-role message, and loop back to step 2.
 *   5. If no tool call is found: the response is final; return it.
 *
 * The runtime is UI-agnostic — it does not import any Compose types.
 * It exposes a [StateFlow]<[AgentState]> for the UI to observe and
 * calls back via lambdas for streaming deltas and final results.
 *
 * v0.3 caps the number of tool iterations per turn at 5 to prevent
 * infinite loops. v0.4 can make this configurable.
 *
 * @property repository the AI HTTP client wrapper.
 * @property toolRegistry registered tools (used to build the prompt).
 * @property toolExecutor runs tool calls.
 */
class AgentRuntime(
    private val repository: AiRepository,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor
) {

    private val parser = ToolCallParser()

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /**
     * Runs a single agent turn:
     *  - [history] is the prior conversation (without the new user message).
     *  - [userMessage] is the new user input.
     *  - [onDelta] is called for each streamed text fragment (can be empty).
     *  - [onFinal] is called with the full assistant response when the
     *    loop completes (whether or not tools were called).
     *
     * On error, [onFinal] is NOT called; instead, the error is written
     * to [state.lastError] and re-thrown to the caller.
     */
    suspend fun runTurn(
        history: List<AiMessage>,
        userMessage: String,
        onDelta: (String) -> Unit,
        onFinal: (String) -> Unit
    ) {
        val systemPrompt = AgentContext.buildSystemPrompt(toolRegistry.describeForPrompt())
        val messages = mutableListOf<AiMessage>()
        messages.add(AiMessage(role = "system", content = systemPrompt))
        messages.addAll(history)
        messages.add(AiMessage(role = "user", content = userMessage))

        val context = AgentContext(messages = messages, systemPrompt = systemPrompt)

        _state.value = AgentState(busy = true, streamingText = "")

        try {
            val finalText = runLoop(context, onDelta)
            _state.value = AgentState.IDLE
            onFinal(finalText)
        } catch (t: Throwable) {
            Timber.w(t, "Agent turn failed")
            _state.value = AgentState.IDLE.copy(lastError = t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    /**
     * Inner loop: stream model response → check for tool call → repeat.
     * Returns the final assistant text (the one that did NOT contain a
     * tool call).
     */
    private suspend fun runLoop(
        context: AgentContext,
        onDelta: (String) -> Unit
    ): String {
        var iteration = 0
        while (true) {
            if (iteration >= context.maxToolIterations) {
                Timber.w("Hit max tool iterations (%d); stopping", context.maxToolIterations)
                return "I've reached the maximum number of tool calls for this turn. " +
                    "Here's what I have so far: ${_state.value.streamingText}"
            }

            // Stream the model's response. Accumulate the full text.
            val accumulator = StringBuilder()
            try {
                repository.streamChat(context.messages.toList()).collect { delta ->
                    accumulator.append(delta)
                    _state.value = _state.value.copy(streamingText = accumulator.toString())
                    onDelta(delta)
                }
            } catch (e: AiException) {
                throw e
            }

            val responseText = accumulator.toString().trim()
            Timber.i("Model iteration %d produced %d chars", iteration, responseText.length)

            // Scan for a tool call.
            when (val parsed = parser.parse(responseText)) {
                is ToolCallParseResult.None -> {
                    // Final response — no tool call.
                    return responseText
                }
                is ToolCallParseResult.Malformed -> {
                    // Treat malformed calls as a tool error so the model
                    // can recover on the next iteration.
                    val result = ToolResult(
                        tool = "(malformed)",
                        ok = false,
                        output = "",
                        errorMessage = "Malformed tool call: ${parsed.error}. Raw: ${parsed.raw.take(200)}",
                        durationMs = 0
                    )
                    recordToolCall("(malformed)", result)
                    context.messages.add(AiMessage(role = "assistant", content = responseText))
                    context.messages.add(
                        AiMessage(
                            role = "tool",
                            content = result.render(),
                            name = "system"
                        )
                    )
                    iteration++
                    continue
                }
                is ToolCallParseResult.Found -> {
                    val call: ToolCall = parsed.call
                    // Add the assistant's tool-call message to the history.
                    context.messages.add(AiMessage(role = "assistant", content = responseText))
                    // Execute the tool.
                    val result = toolExecutor.execute(call) { r ->
                        recordToolCall(call.tool, r)
                    }
                    // Append the tool result so the model can use it on
                    // the next iteration.
                    context.messages.add(
                        AiMessage(
                            role = "tool",
                            content = result.render(),
                            name = call.tool
                        )
                    )
                    iteration++
                    // Loop back to stream another response.
                }
            }
        }
    }

    private fun recordToolCall(toolName: String, result: ToolResult) {
        _state.value = _state.value.copy(
            lastToolCall = toolName,
            lastToolResult = result,
            toolCallsThisTurn = _state.value.toolCallsThisTurn + 1
        )
    }

    /** Resets per-turn counters. Call before starting a new turn. */
    fun resetTurnCounters() {
        _state.value = AgentState.IDLE
    }
}

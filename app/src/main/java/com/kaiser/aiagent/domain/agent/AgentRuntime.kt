package com.kaiser.aiagent.domain.agent

import com.kaiser.aiagent.data.ai.AiException
import com.kaiser.aiagent.data.ai.AiMessage
import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.logging.LogRepository
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
 *   4. If a tool call is found: execute it (going through the
 *      PermissionManager inside [ToolExecutor]), append the tool
 *      result to the context, and loop back to step 2.
 *   5. If no tool call is found: the response is final; return it.
 *
 * The runtime is UI-agnostic — it does not import any Compose types.
 * It exposes a [StateFlow]<[AgentState]> for the UI to observe and
 * calls back via lambdas for streaming deltas and final results.
 *
 * v0.4 changes:
 *  - Permission enforcement moved INTO ToolExecutor (which delegates
 *    to PermissionManager). The runtime no longer needs a separate
 *    onConfirmationRequired callback; the UI observes
 *    PermissionManager.pendingConfirmation instead.
 *  - ToolResult uses the new success/data/error/metadata shape.
 *  - Tool calls pass conversationId through to ToolExecutor so the
 *    PermissionManager can include it in the pending-confirmation
 *    request (useful if the UI wants to display the conversation title
 *    alongside the confirmation dialog).
 *
 * v0.3 caps the number of tool iterations per turn at 5 to prevent
 * infinite loops.
 */
class AgentRuntime(
    private val repository: AiRepository,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val logRepository: LogRepository? = null
) {

    private val parser = ToolCallParser()

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /**
     * Runs a single agent turn.
     *
     * @param history prior conversation (without the new user message).
     * @param userMessage the new user input.
     * @param conversationId optional — passed to ToolExecutor for the
     *   permission confirmation flow.
     * @param onDelta called for each streamed text fragment.
     * @param onFinal called with the full assistant response when the
     *   loop completes (whether or not tools were called).
     *
     * On error, [onFinal] is NOT called; instead, the error is written
     * to [state.lastError] and re-thrown to the caller.
     */
    suspend fun runTurn(
        history: List<AiMessage>,
        userMessage: String,
        conversationId: String? = null,
        onDelta: (String) -> Unit,
        onFinal: (String) -> Unit,
        onStatus: ((String) -> Unit)? = null
    ) {
        val systemPrompt = AgentContext.buildSystemPrompt(toolRegistry.describeForPrompt())
        val messages = mutableListOf<AiMessage>()
        messages.add(AiMessage(role = "system", content = systemPrompt))
        messages.addAll(history)
        messages.add(AiMessage(role = "user", content = userMessage))

        val context = AgentContext(messages = messages, systemPrompt = systemPrompt)

        _state.value = AgentState(busy = true, streamingText = "")

        try {
            val finalText = runLoop(context, conversationId, onDelta, onStatus)
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
        conversationId: String?,
        onDelta: (String) -> Unit,
        onStatus: ((String) -> Unit)? = null
    ): String {
        var iteration = 0
        while (true) {
            if (iteration >= context.maxToolIterations) {
                Timber.w("Hit max tool iterations (%d); stopping", context.maxToolIterations)
                val lastResult = _state.value.lastToolResult
                val lastText = _state.value.streamingText
                return when {
                    lastResult != null && lastResult.success ->
                        "Based on the tool result: ${lastResult.data}"
                    lastText.isNotBlank() -> lastText
                    else -> "I attempted to use a tool but couldn't complete the request. " +
                        "Please try rephrasing your question, or ask me something that " +
                        "doesn't require a tool."
                }
            }

            // Stream the model's response. Accumulate the full text.
            val accumulator = StringBuilder()
            try {
                repository.streamChat(context.messages.toList(), onStatus).collect { delta ->
                    accumulator.append(delta)
                    _state.value = _state.value.copy(streamingText = accumulator.toString())
                    onDelta(delta)
                }
            } catch (e: AiException) {
                throw e
            }

            val responseText = accumulator.toString().trim()
            Timber.i("Model iteration %d produced %d chars", iteration, responseText.length)
            logRepository?.appendUpdateLog(
                "Model iter $iteration (${responseText.length} chars): " +
                    responseText.take(1000).replace("\n", "\\n")
            )

            // Parrot detection.
            if (iteration > 0 && looksLikeParrot(responseText)) {
                Timber.w("Parrot detected on iteration %d: %s", iteration, responseText.take(100))
                logRepository?.appendUpdateLog("PARROT DETECTED — adding stronger nudge")
                context.messages.add(
                    AiMessage(
                        role = "user",
                        content = "Stop repeating yourself. You have already called the tool and received the result. " +
                            "Now answer the user's ORIGINAL question in plain natural-language English. " +
                            "Do not mention tools. Do not mention calling anything. Just answer the question directly."
                    )
                )
                iteration++
                continue
            }

            // Scan for a tool call.
            when (val parsed = parser.parse(responseText)) {
                is ToolCallParseResult.None -> {
                    // Final response — no tool call.
                    return responseText
                }
                is ToolCallParseResult.Malformed -> {
                    val result = ToolResult(
                        success = false,
                        data = "",
                        error = "Malformed tool call: ${parsed.error}. Raw: ${parsed.raw.take(200)}"
                    )
                    recordToolCall("(malformed)", result)
                    context.messages.add(
                        AiMessage(
                            role = "assistant",
                            content = "I'll attempt to call a tool, but my call was malformed."
                        )
                    )
                    context.messages.add(
                        AiMessage(
                            role = "user",
                            content = "[TOOL RESULT] ${result.render()}\n\n" +
                                "Please continue your response to the user, taking this result into account."
                        )
                    )
                    iteration++
                    continue
                }
                is ToolCallParseResult.Found -> {
                    val call: ToolCall = parsed.call
                    // Wrap the tool call as an internal note. Past tense + bracketed
                    // so the model doesn't parrot it back as a future promise.
                    context.messages.add(
                        AiMessage(
                            role = "assistant",
                            content = "[Tool call executed: ${call.tool}]"
                        )
                    )
                    // v0.4: ToolExecutor handles permission enforcement internally
                    // via PermissionManager. CONFIRMATION_REQUIRED tools will
                    // suspend here until the UI resolves the pending confirmation.
                    // v0.5.12: wrap in withTimeout to prevent "stuck in thinking"
                    // when a tool (e.g. search_files on a huge directory tree)
                    // takes too long. 30 seconds is generous for any file operation.
                    val result: ToolResult = try {
                        kotlinx.coroutines.withTimeout(30_000L) {
                            toolExecutor.execute(
                                call = call,
                                conversationId = conversationId,
                                onResult = { toolName, r -> recordToolCall(toolName, r) }
                            )
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Timber.w("Tool %s timed out after 30s", call.tool)
                        ToolResult(
                            success = false,
                            data = "",
                            error = "Tool '${call.tool}' timed out after 30 seconds. The directory may be too large. Try a more specific search."
                        )
                    }
                    // Append the tool result as a user message (text-based tool
                    // calling doesn't use the tool role — see v0.3.3 commit msg).
                    val successOrError = if (result.success) "succeeded" else "failed"
                    context.messages.add(
                        AiMessage(
                            role = "user",
                            content = buildString {
                                appendLine("The ${call.tool} tool $successOrError. Result:")
                                appendLine(result.render())
                                appendLine()
                                appendLine("Using this result, answer the user's original question in plain English. Do not call any more tools. Do not repeat yourself.")
                            }
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

    /**
     * Returns true if [text] looks like the model parroting back a prior
     * internal message rather than giving a real answer.
     */
    private fun looksLikeParrot(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("[tool call executed:") ||
            lower.contains("i'll call the \"") ||
            lower.contains("i'll call the '") ||
            lower.contains("i'll call the ") && lower.contains("tool to look that up")
    }

    /** Resets per-turn counters. Call before starting a new turn. */
    fun resetTurnCounters() {
        _state.value = AgentState.IDLE
    }
}

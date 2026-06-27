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
    private val toolExecutor: ToolExecutor,
    private val logRepository: LogRepository? = null
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
                // v0.3.5: smarter fallback. If a tool WAS successfully
                // called (we have a lastToolResult), surface that. If no
                // tool was called but the model produced text, return the
                // text — it may be a usable answer even without tools.
                // Only show the "couldn't complete" error when we truly
                // have nothing useful.
                val lastResult = _state.value.lastToolResult
                val lastText = _state.value.streamingText
                return when {
                    lastResult != null && lastResult.ok ->
                        "Based on the tool result: ${lastResult.output}"
                    lastText.isNotBlank() ->
                        lastText  // the model's text response, even if no tool ran
                    else ->
                        "I attempted to use a tool but couldn't complete the request. " +
                            "Please try rephrasing your question, or ask me something that " +
                            "doesn't require a tool."
                }
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
            // v0.3.5: log every model response verbatim so we can
            // diagnose parser failures ("Last tool call: (none)") by
            // reading update.log. Truncate to 1000 chars to keep the
            // log readable.
            logRepository?.appendUpdateLog(
                "Model iter $iteration (${responseText.length} chars): " +
                    responseText.take(1000).replace("\n", "\\n")
            )

            // v0.3.7: parrot detection. If the model echoes back a prior
            // wrapped assistant message (e.g. "[Tool call executed: X]")
            // or a prior tool-result directive, it's stuck in a loop.
            // Force a stronger nudge before the next iteration.
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
                    // CRITICAL: many OpenAI-compatible providers (Groq,
                    // OpenAI itself, etc.) reject assistant messages that
                    // contain only raw JSON — the API expects assistant
                    // messages to be natural-language text. Wrap the
                    // tool-call JSON in plain prose so history replay
                    // doesn't trip provider validation (400 SSE errors).
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
                    // CRITICAL: same as above — do NOT replay raw JSON as
                    // an assistant message. Wrap it in prose.
                    //
                    // v0.3.6 bugfix: use ${call.tool} (braces) instead of
                    // $call.tool (no braces). Kotlin parses "$call.tool" as
                    // "${call}.tool" — stringifying the whole ToolCall object
                    // then appending the literal ".tool" — which produced
                    // garbage like 'ToolCall(tool=device_info, arguments={}).tool'
                    // in the assistant message. The braces force Kotlin to
                    // access the .tool field directly.
                    //
                    // v0.3.7 bugfix: the previous wording "I'll call the X
                    // tool to look that up." was being PARROTED back by the
                    // model in iteration 2 — the model saw its own promise
                    // and just repeated it instead of giving a real answer.
                    // New wording is past-tense, bracketed, and clearly an
                    // internal system note that the model won't confuse
                    // with its user-facing response.
                    val wrappedAssistantMessage = "[Tool call executed: ${call.tool}]"
                    context.messages.add(
                        AiMessage(
                            role = "assistant",
                            content = wrappedAssistantMessage
                        )
                    )
                    // Execute the tool.
                    val result = toolExecutor.execute(call) { r ->
                        recordToolCall(call.tool, r)
                    }
                    // Append the tool result as a user message (NOT a
                    // tool message — text-based tool calling doesn't use
                    // the tool role, and using role=tool without a
                    // matching tool_call_id will cause 400 errors on
                    // strict providers like Groq).
                    //
                    // v0.3.7: streamlined the directive to be shorter and
                    // more imperative. The model was getting confused by
                    // the long instruction block.
                    val successOrError = if (result.ok) "succeeded" else "failed"
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
     * internal message rather than giving a real answer. Triggers the
     * stronger nudge in [runLoop].
     *
     * Detects:
     *  - "[Tool call executed: ...]" — the wrapped assistant message
     *  - "I'll call the ... tool" — old wording the model might still echo
     *  - "I'll call the ... tool to look that up" — full old wording
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

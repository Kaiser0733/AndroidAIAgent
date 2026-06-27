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
                // v0.3.4: instead of dumping raw tool-call JSON at the user,
                // synthesize a helpful answer from the last tool result if
                // we have one. Falls back to a clean error message if not.
                val lastResult = _state.value.lastToolResult
                return if (lastResult != null && lastResult.ok) {
                    "Based on the tool result: ${lastResult.output}"
                } else {
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
                    context.messages.add(
                        AiMessage(
                            role = "assistant",
                            content = "I'll call the \"$call.tool\" tool to look that up."
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
                    // v0.3.4: phrasing is deliberately assertive ("you
                    // already called X, here is its result, NOW ANSWER
                    // THE USER IN PLAIN TEXT"). Without this emphasis,
                    // Llama 3.3 was observed re-emitting the same tool
                    // call up to the 5-iteration cap.
                    val successOrError = if (result.ok) "succeeded" else "failed"
                    context.messages.add(
                        AiMessage(
                            role = "user",
                            content = buildString {
                                appendLine("You just called the \"$call.tool\" tool and it $successOrError. Here is the result:")
                                appendLine()
                                appendLine("[TOOL RESULT for ${call.tool}] ${result.render()}")
                                appendLine()
                                appendLine("IMPORTANT: You already have this information. DO NOT call \"${call.tool}\" again.")
                                appendLine("DO NOT emit any more JSON tool calls. Instead, answer the user's original question in plain natural-language English, using the result above.")
                                appendLine("Your next response must be plain text — NOT JSON.")
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

    /** Resets per-turn counters. Call before starting a new turn. */
    fun resetTurnCounters() {
        _state.value = AgentState.IDLE
    }
}

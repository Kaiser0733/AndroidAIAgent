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
        // v0.6.5: bumped from 8 → 10. The full UI chain is:
        //   open_app(1) → read_screen(2) → tap_text(3) → wait_seconds(4)
        //   → type_text(5) → tap_text(6) → wait_seconds(7) → read_screen(8)
        //   → final text answer(9)
        // With maxIterations=8 the agent hit the limit BEFORE it could
        // emit the final text answer, causing a blank response.
        val maxIterations = 10
        var blankRetries = 0
        // v0.6.5: track whether the last tool call was read_screen.
        // Used to detect hallucination — if the model tries to give
        // a final answer describing screen contents without having
        // just called read_screen, we inject a reminder.
        var lastToolWasReadScreen = false

        while (true) {
            if (iteration >= maxIterations) {
                Timber.w("Hit max iterations (%d)", maxIterations)
                return _state.value.streamingText.ifBlank {
                    "I reached the maximum number of tool calls for this turn."
                }
            }

            val textBuilder = StringBuilder()
            val toolCallAccumulator = mutableMapOf<Int, ToolCallAccum>()

            // v0.6.3: auto-retry on 429 rate limit. Groq's free tier
            // allows 30 req/min — a multi-step UI chain can hit this
            // mid-turn. Instead of failing the whole turn, sleep 20s
            // and retry the same iteration (up to 2 retries).
            var streamAttempts = 0
            var streamed = false
            while (!streamed && streamAttempts < 3) {
                streamAttempts++
                try {
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
                    streamed = true
                } catch (e: Throwable) {
                    val msg = (e.message ?: "").lowercase()
                    val isRateLimit = msg.contains("429") || msg.contains("rate limit")
                    if (!isRateLimit || streamAttempts >= 3) {
                        // Surface a friendlier message for 429 so the user
                        // knows to wait rather than think the app is broken.
                        if (isRateLimit) {
                            throw AiException(
                                "Rate limited by the AI provider after $streamAttempts attempts. " +
                                "Wait 30-60 seconds and try again. (Groq free tier = 30 requests/min.)"
                            )
                        }
                        throw e
                    }
                    // Rate limited — wait 20s and retry the SAME iteration.
                    Timber.w("429 rate limit on attempt %d — waiting 20s and retrying", streamAttempts)
                    onStatus?.invoke("Rate limited — waiting 20s and retrying (attempt $streamAttempts/3)...")
                    kotlinx.coroutines.delay(20_000L)
                    // Clear partial state before retrying.
                    textBuilder.clear()
                    toolCallAccumulator.clear()
                    _state.value = _state.value.copy(streamingText = "")
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

                    // v0.6.5: track whether the last tool was read_screen.
                    lastToolWasReadScreen = tc.function.name == "read_screen"

                    // v0.6.2: bumped from 800 → 1500 chars so the model
                    // can see more of read_screen output (which can list
                    // many UI elements).
                    val truncatedData = if (result.data.length > 1500)
                        result.data.take(1500) + "..." else result.data

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
                // v0.6.3: small pause between tool iterations to ease
                // Groq's 30 req/min free-tier limit. Without this, a
                // 6-step UI chain can fire 6 requests in 5 seconds and
                // trip the rate limiter on step 4 or 5.
                kotlinx.coroutines.delay(1500L)
                continue
            }

            // v0.6.5: Anti-hallucination check. If the model is about to
            // give a final text answer that describes screen contents
            // (e.g. "I can see results like...") but the last tool was
            // NOT read_screen, inject a reminder and retry. This stops
            // the model from inventing results it never verified.
            val looksLikeScreenDescription = responseText.contains("I can see") ||
                responseText.contains("I see") ||
                responseText.contains("results like") ||
                responseText.contains("showing") ||
                responseText.contains("found") && responseText.contains("BBS")
            if (looksLikeScreenDescription && !lastToolWasReadScreen && blankRetries < 1) {
                blankRetries++
                Timber.w("Possible hallucination detected — model describing screen without read_screen. Retrying with reminder.")
                messages.add(AiMessage(role = "assistant", content = responseText))
                messages.add(AiMessage(
                    role = "user",
                    content = "STOP. You just described screen contents without calling read_screen first. " +
                        "That is a hallucination — you cannot know what is on screen without reading it. " +
                        "Call read_screen NOW, then give your final answer based ONLY on what it returns. " +
                        "Do not invent results, video titles, or screen contents."
                ))
                _state.value = _state.value.copy(streamingText = "")
                iteration++
                kotlinx.coroutines.delay(1500L)
                continue
            }

            // v0.6.5: blank response check. If the model returned empty
            // text with no tool calls, retry once with a nudge. This
            // fixes the "blank first message" bug where the model
            // sometimes emits just whitespace on the first call of a
            // fresh conversation.
            if (responseText.isBlank()) {
                if (blankRetries < 2) {
                    blankRetries++
                    Timber.w("Blank response on iteration %d — retrying with nudge (attempt %d)", iteration, blankRetries)
                    messages.add(AiMessage(role = "assistant", content = ""))
                    messages.add(AiMessage(
                        role = "user",
                        content = "Your previous response was empty. Please either:\n" +
                            "1. Call the next tool to continue the task, OR\n" +
                            "2. Answer the user's original question in plain English.\n" +
                            "Do not return an empty response."
                    ))
                    _state.value = _state.value.copy(streamingText = "")
                    iteration++
                    kotlinx.coroutines.delay(1500L)
                    continue
                }
                Timber.w("Still blank after %d retries — returning default", blankRetries)
                return "I attempted your request but couldn't generate a final response. " +
                    "Please try again — sometimes the AI model returns an empty response on the first try."
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

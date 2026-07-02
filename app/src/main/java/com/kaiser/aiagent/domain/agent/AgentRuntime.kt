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
            // mid-turn. Instead of failing the whole turn, sleep 10s
            // and retry the same iteration (up to 2 retries).
            // v0.6.7: reduced from 20s×3 to 10s×2 — the old 60s wait
            // was too long and made the agent look frozen.
            var streamAttempts = 0
            var streamed = false
            while (!streamed && streamAttempts < 3) {
                streamAttempts++
                try {
                    // v0.6.7: hard 2-minute watchdog around the stream.
                    // Even if the read timeout fails to fire (e.g. the
                    // provider sends keepalive bytes but no real data),
                    // this guarantees the iteration can't hang forever.
                    kotlinx.coroutines.withTimeoutOrNull(120_000L) {
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
                    } ?: run {
                        // withTimeoutOrNull returned null → timed out
                        throw AiException("Stream timed out after 120 seconds with no data. " +
                            "The AI provider may be overloaded. Try again.")
                    }
                    streamed = true
                } catch (e: Throwable) {
                    val msg = (e.message ?: "").lowercase()
                    val isRateLimit = msg.contains("429") || msg.contains("rate limit")
                    val isTimeout = msg.contains("timed out") || msg.contains("sockettimeout")
                    if (!isRateLimit && !isTimeout) {
                        throw e
                    }
                    if (streamAttempts >= 3) {
                        if (isRateLimit) {
                            throw AiException(
                                "Rate limited by the AI provider after $streamAttempts attempts. " +
                                "Wait 60 seconds and try again. (Free tiers: Groq = 30 req/min, " +
                                "Gemini = 15 req/min.) Tip: simple questions like 'hello' use 1 " +
                                "request; multi-step tasks like 'open YouTube and search X' use " +
                                "6-8 requests in a row."
                            )
                        }
                        throw e
                    }
                    // v0.6.8: parse Retry-After from the error message.
                    // AiService embeds it as "retry-after=Ns" on 429.
                    // If present, wait that long + 2s buffer. Otherwise
                    // fall back to a fixed 30s wait (was 10s — too short
                    // for Groq's 60s rate limit window to fully reset).
                    val retryAfterMatch = Regex("retry-after=(\\d+)s").find(msg)
                    val waitMs = if (isRateLimit && retryAfterMatch != null) {
                        val secs = retryAfterMatch.groupValues[1].toLong()
                        (secs + 2) * 1000L  // provider-specified wait + 2s buffer
                    } else if (isRateLimit) {
                        30_000L  // no Retry-After header — wait 30s
                    } else {
                        10_000L  // timeout — wait 10s
                    }
                    val waitReason = if (isRateLimit) "rate limit" else "timeout"
                    val waitSec = waitMs / 1000
                    Timber.w("%s on attempt %d — waiting %ds and retrying", waitReason, streamAttempts, waitSec)
                    onStatus?.invoke("Hit $waitReason — waiting ${waitSec}s and retrying (attempt $streamAttempts/3)...")
                    kotlinx.coroutines.delay(waitMs)
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
                    // v0.7.5: script tools (youtube_*) legitimately need more
                    // time — they open apps, wait for windows, poll for results.
                    // Old 30s timeout killed youtube_search mid-execution (it
                    // can take 31s+ on slow devices), leaving the agent stuck.
                    val toolTimeoutMs = if (tc.function.name.startsWith("youtube_")) 60_000L else 30_000L
                    val result: ToolResult = try {
                        kotlinx.coroutines.withTimeout(toolTimeoutMs) {
                            toolExecutor.execute(call, conversationId) { name, r ->
                                recordToolCall(name, r)
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        ToolResult(false, "", "Tool '${tc.function.name}' timed out after ${toolTimeoutMs/1000}s.")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // v0.7.5: catch ALL cancellation (not just Timeout) so
                        // a cancelled tool doesn't kill the whole turn.
                        ToolResult(false, "", "Tool '${tc.function.name}' was cancelled: ${e.message ?: "unknown"}")
                    }

                    // v0.6.5: track whether the last tool was read_screen.
                    lastToolWasReadScreen = tc.function.name == "read_screen"

                    // v0.6.9: smarter truncation. read_screen can return
                    // 2000+ chars (many UI elements); other tools are
                    // usually small. Cap read_screen at 1000 chars,
                    // other tools at 1500. This keeps the request size
                    // down so we don't blow Groq's 6000 tokens/min limit.
                    val cap = if (tc.function.name == "read_screen") 1000 else 1500
                    val truncatedData = if (result.data.length > cap)
                        result.data.take(cap) + "..." else result.data

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
                kotlinx.coroutines.delay(2000L)

                // v0.6.9: history compaction. Each iteration re-sends
                // the full message history to Groq, and Groq's free
                // tier has a 6000 tokens/min limit. By iteration 4-5
                // the history can be 3000+ tokens (system prompt +
                // accumulated tool results), so 4 calls = 12000+ tokens
                // → over the limit.
                //
                // Fix: after iteration 3, compact old tool results to
                // a 1-line summary. Keeps the history small so later
                // iterations stay well under the token limit.
                if (iteration >= 3) {
                    compactHistory(messages)
                }
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
                kotlinx.coroutines.delay(2000L)
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
                    kotlinx.coroutines.delay(2000L)
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

    /**
     * v0.6.9: Shrinks old tool-result messages in [messages] to a
     * 1-line summary. Keeps only the LAST tool result intact (the
     * model needs it for the current decision). Older tool results
     * get replaced with "[tool <name>: <status>, 1-line summary]".
     *
     * This dramatically reduces token count on later iterations of
     * a multi-step UI chain, which is the main cause of Groq's
     * 6000 tokens/min free-tier limit being hit mid-turn.
     *
     * The system message, user message, and the most recent assistant
     * + tool messages are always preserved unchanged.
     */
    private fun compactHistory(messages: MutableList<AiMessage>) {
        // Find indices of tool messages, keeping the last 2 intact.
        val toolIndices = messages.indices.filter { messages[it].role == "tool" }
        if (toolIndices.size <= 2) return  // nothing to compact

        val toCompact = toolIndices.dropLast(2)  // keep last 2 verbatim
        for (i in toCompact) {
            val msg = messages[i]
            val original = msg.content ?: ""
            if (original.length <= 80) continue  // already small
            // Build a 1-line summary: tool name + first 60 chars of result.
            val toolName = msg.name ?: "tool"
            val summary = original.take(60).replace("\n", " ")
            messages[i] = msg.copy(
                content = "[compacted $toolName: $summary...]"
            )
        }
        Timber.i("Compacted %d old tool results to summaries", toCompact.size)
    }

    fun resetTurnCounters() { _state.value = AgentState.IDLE }

    private class ToolCallAccum {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()
    }
}

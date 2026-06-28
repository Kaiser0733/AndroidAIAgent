package com.kaiser.aiagent.domain.agent

import com.kaiser.aiagent.data.ai.AiMessage
import com.kaiser.aiagent.domain.tools.ToolResult

/**
 * Immutable snapshot of the agent's state at a point in time. The UI
 * observes this via a StateFlow exposed by [AgentRuntime].
 *
 * The state is intentionally minimal — it tracks only what the UI
 * needs to render the agent's activity. Conversation history lives in
 * the [ConversationRepository] and is not duplicated here.
 */
data class AgentState(
    /** Whether the agent is currently producing a response. */
    val busy: Boolean = false,
    /** Incremental assistant text being streamed (empty when idle). */
    val streamingText: String = "",
    /** Last tool call attempted, if any. */
    val lastToolCall: String? = null,
    /** Last tool result, if any. */
    val lastToolResult: ToolResult? = null,
    /** Number of tool calls made in the current turn. */
    val toolCallsThisTurn: Int = 0,
    /** Last error message, if any. */
    val lastError: String? = null
) {
    companion object {
        val IDLE = AgentState()
    }
}

/**
 * Per-turn context object passed around inside [AgentRuntime]. Holds
 * the working message list (which grows as tool calls are added) and
 * the system prompt to use.
 *
 * Created fresh for each user message. The runtime mutates it in place
 * across tool-call iterations of the same turn.
 */
data class AgentContext(
    /** The full message list sent to the model on each iteration. */
    val messages: MutableList<AiMessage>,
    /** System prompt including the tool catalog. */
    val systemPrompt: String,
    /** Max tool-call iterations before giving up (prevents infinite loops). */
    val maxToolIterations: Int = 5
) {
    companion object {
        /**
         * Builds the default system prompt for v0.4.5 — a File Agent with
         * a CONCISE prompt to minimize token usage and rate-limit hits.
         *
         * v0.4.5 change: the v0.4.3 prompt was ~2000 tokens (tool catalog
         * + 3 examples + 7 rules). Every chat message sends this prompt,
         * so on Groq's 30 req/min free tier with 500K tokens/day, a long
         * prompt burns through the daily budget fast. Trimmed to ~800
         * tokens by removing verbose examples and tightening language.
         */
        fun buildSystemPrompt(toolCatalog: String): String = buildString {
            appendLine("You are Android AI Agent, a personal assistant on Android.")
            appendLine("You can answer questions AND manage files (find, read, create).")
            appendLine()
            appendLine(toolCatalog)
            appendLine("To call a tool, respond with ONLY: {\"tool\":\"<name>\",\"arguments\":<json>}")
            appendLine("No markdown fences, no prose around it.")
            appendLine()
            appendLine("RULES:")
            appendLine("1. After a tool result, answer the user in plain text. Don't repeat tool calls.")
            appendLine("2. Final answer must be plain text, NOT JSON.")
            appendLine("3. Don't invent tool results.")
            appendLine("4. For file searches use search_files. For listings use list_files. For reading use read_text_file. For creating use create_folder/create_text_file (these need confirmation).")
        }
    }
}

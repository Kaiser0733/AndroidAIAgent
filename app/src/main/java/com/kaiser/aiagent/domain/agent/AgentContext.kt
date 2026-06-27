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
         * Builds the default system prompt that:
         *  1. Tells the model it's an AI agent on Android.
         *  2. Lists the available tools.
         *  3. Specifies the JSON tool-call format.
         *  4. Tells the model to keep tool calls separate from prose.
         */
        fun buildSystemPrompt(toolCatalog: String): String = buildString {
            appendLine("You are Android AI Agent, a personal AI assistant running on the user's Android device.")
            appendLine("You are helpful, concise, and honest. When you don't know something, say so.")
            appendLine()
            appendLine(toolCatalog)
            appendLine("To call a tool, respond with ONLY a JSON object in this exact format (no markdown fences, no prose):")
            appendLine("""{"tool": "<tool_name>", "arguments": <json_object>}""")
            appendLine("After you call a tool, you will receive its result and can continue your response.")
            appendLine("If a tool returns an error, acknowledge it and try an alternative approach.")
            appendLine("Do not call tools you have not been told about.")
            appendLine("Do not invent tool results — only use tools that have actually been executed.")
        }
    }
}

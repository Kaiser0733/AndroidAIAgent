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
         *  4. Gives explicit anti-loop + after-tool guidance so the model
         *     doesn't repeatedly emit the same tool call.
         *
         * v0.3.4 added the "CRITICAL RULES" section — without it, Llama 3.3
         * (Groq) was observed emitting the same tool call up to the 5-iteration
         * cap without ever producing a final natural-language answer.
         */
        fun buildSystemPrompt(toolCatalog: String): String = buildString {
            appendLine("You are Android AI Agent, a personal AI assistant running on the user's Android device.")
            appendLine("You are helpful, concise, and honest. When you don't know something, say so.")
            appendLine()
            appendLine(toolCatalog)
            appendLine("To call a tool, respond with ONLY a JSON object in this exact format (no markdown fences, no prose, no extra text before or after):")
            appendLine("""{"tool": "<tool_name>", "arguments": <json_object>}""")
            appendLine()
            appendLine("CRITICAL RULES — read carefully:")
            appendLine("1. After you receive a [TOOL RESULT] message, you MUST give a final natural-language answer to the user. Do NOT emit another tool call for the same information.")
            appendLine("2. Never repeat a tool call that you have already made in this conversation. If you already received a result, use it.")
            appendLine("3. Each tool call should be made at most ONCE per user question. If the result is sufficient, answer directly.")
            appendLine("4. Your final answer must be plain natural-language text — NOT JSON. The user sees your text response, not your tool calls.")
            appendLine("5. If a tool returns an error, acknowledge it briefly and either try a different approach or tell the user you cannot help with that specific request.")
            appendLine("6. Do not call tools you have not been told about.")
            appendLine("7. Do not invent tool results — only use tools that have actually been executed.")
            appendLine()
            appendLine("EXAMPLE FLOW:")
            appendLine("User: What time is it?")
            appendLine("Assistant: {\"tool\": \"get_time\", \"arguments\": {}}")
            appendLine("User: [TOOL RESULT for get_time] {\"iso\":\"2026-06-27T15:30:45+05:30\",\"timezone\":\"Asia/Calcutta\",\"epoch_ms\":1781149845000}")
            appendLine("Assistant: It's currently 3:30 PM IST (Asia/Calcutta) on June 27, 2026.")
            appendLine()
            appendLine("In the example above, the final assistant message is plain text — NOT JSON. Always end with plain text.")
        }
    }
}

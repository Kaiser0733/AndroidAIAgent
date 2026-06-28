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
         * Builds the default system prompt for v0.4.3 — a File Agent that
         * can discover and create files freely. The model is encouraged
         * to use the available tools proactively when the user asks for
         * file operations.
         *
         * v0.4.3 change: removed the heavy "hard refusal policy" from
         * earlier versions. The previous policy was causing the model
         * (Llama 3.3) to over-refuse legitimate file operations like
         * "create a folder" or "read a file" — it was treating them as
         * forbidden because the safety language was too aggressive.
         *
         * Actual safety is still enforced at the architecture level:
         *  - The 5 blocked tools (delete_file, move_file, rename_file,
         *    accessibility_action, app_control) are registered with
         *    BLOCKED permission level. The ToolExecutor refuses to run
         *    them and returns a denial result — the model can try to
         *    call them but they will never execute.
         *  - The 2 CONFIRMATION_REQUIRED tools (create_folder,
         *    create_text_file) suspend until the user approves via a
         *    dialog. The user can deny.
         *  - No shell-execution, APK-install, or accessibility tools
         *    exist in the registry at all — there's nothing for the
         *    model to call.
         *
         * So the prompt no longer needs to preach about safety. The
         * tools the model CAN see are all safe to use.
         */
        fun buildSystemPrompt(toolCatalog: String): String = buildString {
            appendLine("You are Android AI Agent, a personal AI assistant running on the user's Android device.")
            appendLine("You are helpful, concise, and honest. When you don't know something, say so.")
            appendLine()
            appendLine("You have two main capabilities:")
            appendLine("  1. Answer questions and have conversations like a normal assistant.")
            appendLine("  2. Discover, read, and create files on the user's device.")
            appendLine()
            appendLine(toolCatalog)
            appendLine("To call a tool, respond with ONLY a JSON object in this exact format (no markdown fences, no prose, no extra text before or after):")
            appendLine("""{"tool": "<tool_name>", "arguments": <json_object>}""")
            appendLine()
            appendLine("CRITICAL RULES — read carefully:")
            appendLine("1. After you receive a tool result, you MUST give a final natural-language answer to the user. Do NOT emit another tool call for the same information.")
            appendLine("2. Never repeat a tool call that you have already made in this conversation. If you already received a result, use it.")
            appendLine("3. Each tool call should be made at most ONCE per user question. If the result is sufficient, answer directly.")
            appendLine("4. Your final answer must be plain natural-language text — NOT JSON. The user sees your text response, not your tool calls.")
            appendLine("5. If a tool returns an error, acknowledge it briefly and either try a different approach or tell the user you cannot help with that specific request.")
            appendLine("6. Do not call tools you have not been told about.")
            appendLine("7. Do not invent tool results — only use tools that have actually been executed.")
            appendLine()
            appendLine("FILE TOOL GUIDANCE — use these PROACTIVELY:")
            appendLine("- When the user asks to 'find', 'search', or 'look for' files, use search_files. Common queries:")
            appendLine("    'find all PDF files'     -> {\"tool\":\"search_files\",\"arguments\":{\"query\":\"\",\"extensions\":[\"pdf\"]}}")
            appendLine("    'search for physics notes' -> {\"tool\":\"search_files\",\"arguments\":{\"query\":\"physics\"}}")
            appendLine("    'find images'            -> {\"tool\":\"search_files\",\"arguments\":{\"query\":\"\",\"extensions\":[\"jpg\",\"png\",\"gif\",\"webp\"]}}")
            appendLine("- When the user asks what's in a folder, use list_files. If you don't know the path, call list_storage_roots first.")
            appendLine("- When the user asks about a specific file's size, type, or date, use file_info.")
            appendLine("- When the user asks to read a text file, use read_text_file. For binary files (PDFs, images, Office docs), use file_info instead and tell the user the file's metadata.")
            appendLine("- When the user asks to 'create', 'make', or 'add' a folder, use create_folder (requires confirmation). Example:")
            appendLine("    {\"tool\":\"create_folder\",\"arguments\":{\"path\":\"/storage/emulated/0/Documents\",\"name\":\"Physics\"}}")
            appendLine("- When the user asks to 'create', 'write', or 'save' a text file, use create_text_file (requires confirmation). Example:")
            appendLine("    {\"tool\":\"create_text_file\",\"arguments\":{\"path\":\"/storage/emulated/0/Documents\",\"name\":\"notes.txt\",\"content\":\"Hello world\"}}")
            appendLine("- When the user asks 'do you remember...' or 'what do you know about...', use search_memory.")
            appendLine()
            appendLine("CONFIRMATION FLOW:")
            appendLine("- Tools marked CONFIRMATION_REQUIRED will prompt the user for approval before running.")
            appendLine("- Tell the user you need to perform the action and explain what will happen, then emit the tool call.")
            appendLine("- If the user denies, acknowledge and suggest alternatives.")
            appendLine()
            appendLine("EXAMPLE FLOWS:")
            appendLine()
            appendLine("User: What time is it?")
            appendLine("Assistant: {\"tool\": \"get_time\", \"arguments\": {}}")
            appendLine("User: [TOOL RESULT] {\"iso\":\"2026-06-27T15:30:45+05:30\",...}")
            appendLine("Assistant: It's currently 3:30 PM IST on June 27, 2026.")
            appendLine()
            appendLine("User: Find all PDF files")
            appendLine("Assistant: {\"tool\": \"search_files\", \"arguments\": {\"query\":\"\",\"extensions\":[\"pdf\"]}}")
            appendLine("User: [TOOL RESULT] {\"matches\":[...],\"match_count\":3}")
            appendLine("Assistant: I found 3 PDF files on your device: ...")
            appendLine()
            appendLine("User: Create a folder called Physics in Documents")
            appendLine("Assistant: I'll create a folder called \"Physics\" in your Documents directory. Please confirm when prompted.")
            appendLine("Assistant: {\"tool\": \"create_folder\", \"arguments\": {\"path\":\"/storage/emulated/0/Documents\",\"name\":\"Physics\"}}")
            appendLine("User: [TOOL RESULT] {\"path\":\"...\",\"created\":true}")
            appendLine("Assistant: I've created the Physics folder in your Documents directory.")
            appendLine()
            appendLine("In every example, the final assistant message is plain text — NOT JSON. Always end with plain text.")
        }
    }
}

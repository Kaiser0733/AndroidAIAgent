package com.kaiser.aiagent.domain.tools

import com.kaiser.aiagent.data.ai.AiToolDefinition
import com.kaiser.aiagent.data.ai.AiToolFunctionDef

class ToolRegistry {

    private val tools: MutableMap<String, AgentTool> = LinkedHashMap()

    fun register(tool: AgentTool) { tools[tool.name] = tool }
    fun unregister(name: String) { tools.remove(name) }
    operator fun get(name: String): AgentTool? = tools[name]
    fun all(): List<AgentTool> = tools.values.toList()
    fun contains(name: String): Boolean = name in tools

    /**
     * v0.6: Generates OpenAI-compatible tool definitions for native
     * function calling. Each tool becomes an AiToolDefinition with
     * a proper JSON schema.
     */
    fun toJsonDefinitions(): List<AiToolDefinition> {
        return tools.values.map { tool ->
            AiToolDefinition(
                type = "function",
                function = AiToolFunctionDef(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parametersJsonSchema()
                )
            )
        }
    }

    fun describeForPrompt(): String {
        if (tools.isEmpty()) return "No tools are available."
        return buildString {
            appendLine("You are Android AI Agent, a personal assistant on an Android phone.")
            appendLine("You can answer questions, manage files, and open apps.")
            appendLine()
            appendLine("Available tools (${tools.size}):")
            tools.values.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description}")
            }
            appendLine()
            appendLine("RULES:")
            appendLine("1. After a tool result, answer the user in plain text. Don't repeat tool calls.")
            appendLine("2. Final answer must be plain text, NOT JSON.")
            appendLine("3. Don't invent tool results. Only report what the tool actually returned.")
            appendLine("4. You CAN delete, move, and rename files. Do NOT refuse these requests.")
            appendLine("5. open_app only LAUNCHES an app. It does NOT search, tap, or type inside the app.")
            appendLine("   If the user asks to search something on YouTube, open YouTube and tell them")
            appendLine("   to search manually. Do NOT claim you searched or found results — you cannot")
            appendLine("   interact with other apps yet.")
            appendLine("6. Be honest about your limitations. If you can't do something, say so.")
        }
    }

    fun stats(): ToolStats {
        val byLevel = tools.values.groupBy { it.permissionLevel }
        return ToolStats(
            total = tools.size,
            safe = byLevel[ToolPermissionLevel.SAFE]?.size ?: 0,
            confirmationRequired = byLevel[ToolPermissionLevel.CONFIRMATION_REQUIRED]?.size ?: 0,
            blocked = byLevel[ToolPermissionLevel.BLOCKED]?.size ?: 0
        )
    }

    data class ToolStats(val total: Int, val safe: Int, val confirmationRequired: Int, val blocked: Int)
}

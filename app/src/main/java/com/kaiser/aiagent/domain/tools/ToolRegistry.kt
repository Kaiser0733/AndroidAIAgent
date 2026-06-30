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
            appendLine("You can answer questions AND manage files (find, read, create, delete, move, rename).")
            appendLine()
            appendLine("Available tools (${tools.size}):")
            tools.values.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description}")
            }
            appendLine()
            appendLine("RULES:")
            appendLine("1. After a tool result, answer the user in plain text. Don't repeat tool calls.")
            appendLine("2. Final answer must be plain text, NOT JSON.")
            appendLine("3. Don't invent tool results.")
            appendLine("4. You CAN delete, move, and rename files. Do NOT refuse these requests.")
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

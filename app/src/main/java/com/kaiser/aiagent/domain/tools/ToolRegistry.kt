package com.kaiser.aiagent.domain.tools

/**
 * Mutable registry of [AgentTool]s. Tools register themselves at app
 * startup (see [com.kaiser.aiagent.di.ToolsModule]); the agent queries
 * the registry when (a) building the system prompt (so the model knows
 * what tools exist) and (b) executing a tool call.
 *
 * v0.4 additions:
 *  - [describeForPrompt] now includes the permission level per tool
 *    so the model understands which tools need confirmation.
 *  - [stats] returns a count by permission level for the Debug screen.
 *  - Tools with [ToolPermissionLevel.BLOCKED] are still registered (so
 *    the Debug screen can show them) but the ToolExecutor will refuse
 *    to execute them. This lets the user see "yes, the codebase knows
 *    about DeleteFileTool, but it's blocked by policy".
 */
class ToolRegistry {

    private val tools: MutableMap<String, AgentTool> = LinkedHashMap()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    operator fun get(name: String): AgentTool? = tools[name]

    fun all(): List<AgentTool> = tools.values.toList()

    /** True if a tool with the given name is registered. */
    fun contains(name: String): Boolean = name in tools

    /**
     * Renders a human-readable catalog of all registered tools, suitable
     * for embedding in the system prompt so the model knows what it can
     * call. v0.4 includes the permission level per tool.
     */
    fun describeForPrompt(): String {
        if (tools.isEmpty()) return "No tools are available."
        return buildString {
            appendLine("Available tools:")
            appendLine()
            tools.values.forEachIndexed { i, tool ->
                appendLine("${i + 1}. ${tool.name} (${tool.permissionLevel.name})")
                appendLine("   ${tool.description}")
                appendLine("   Arguments: ${tool.argumentsSchema}")
                appendLine()
            }
            appendLine("Permission levels:")
            appendLine("  SAFE — runs immediately.")
            appendLine("  CONFIRMATION_REQUIRED — the user must approve before the tool runs.")
            appendLine("  BLOCKED — the tool is forbidden (deletion, moving, renaming, APK install,")
            appendLine("            shell execution, messaging, app control, accessibility).")
            appendLine("            If the user asks for any of these, refuse politely and explain why.")
        }
    }

    /** Returns a count of registered tools grouped by permission level. */
    fun stats(): ToolStats {
        val byLevel = tools.values.groupBy { it.permissionLevel }
        return ToolStats(
            total = tools.size,
            safe = byLevel[ToolPermissionLevel.SAFE]?.size ?: 0,
            confirmationRequired = byLevel[ToolPermissionLevel.CONFIRMATION_REQUIRED]?.size ?: 0,
            blocked = byLevel[ToolPermissionLevel.BLOCKED]?.size ?: 0
        )
    }

    data class ToolStats(
        val total: Int,
        val safe: Int,
        val confirmationRequired: Int,
        val blocked: Int
    )
}

package com.kaiser.aiagent.domain.tools

/**
 * Mutable registry of [AgentTool]s. Tools register themselves at app
 * startup (see [com.kaiser.aiagent.di.ToolsModule]); the agent queries
 * the registry when (a) building the system prompt (so the model knows
 * what tools exist) and (b) executing a tool call.
 *
 * The registry is intentionally simple — a map by name. v0.4+ can add
 * categories, enable/disable flags, and per-tool permissions.
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
     * call. Example output:
     *
     * ```
     * Available tools:
     *
     * 1. get_time
     *    Returns the current local time.
     *    Arguments: {}
     *
     * 2. app_info
     *    Returns the app version and build number.
     *    Arguments: {}
     * ```
     */
    fun describeForPrompt(): String {
        if (tools.isEmpty()) return "No tools are available."
        return buildString {
            appendLine("Available tools:")
            appendLine()
            tools.values.forEachIndexed { i, tool ->
                appendLine("${i + 1}. ${tool.name}")
                appendLine("   ${tool.description}")
                appendLine("   Arguments: ${tool.argumentsSchema}")
                appendLine()
            }
        }
    }
}

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
            appendLine("You can answer questions, manage files, open apps, and DRIVE other apps")
            appendLine("via the accessibility service — tap buttons, type into search boxes, scroll, etc.")
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
            appendLine("5. Be honest about your limitations. If a tool returns ERROR, tell the user.")
            appendLine()
            appendLine("MULTI-STEP UI AUTOMATION:")
            appendLine("When the user asks you to do something IN another app (e.g. 'open YouTube")
            appendLine("and search for BBS Racing', 'open WhatsApp and send a message to Mom'), you")
            appendLine("must CHAIN these tools in order:")
            appendLine("  Step 1: open_app  — launches the target app and waits for it to come up.")
            appendLine("  Step 2: read_screen — reads what is currently visible (buttons, search boxes).")
            appendLine("  Step 3: tap_text   — taps the search icon/button by matching its label.")
            appendLine("  Step 4: type_text  — types the user's query into the now-focused search box.")
            appendLine("  Step 5: tap_text   — taps the search/submit button OR presses enter (you can")
            appendLine("                       also just type_text with a trailing newline if appropriate).")
            appendLine("  Step 6: read_screen — verifies the results are showing, then answer the user.")
            appendLine()
            appendLine("CRITICAL: do NOT stop after open_app and tell the user to 'search manually'.")
            appendLine("The whole point of v0.6.2 is that you CAN drive other apps. If a step fails,")
            appendLine("retry once with a slightly different text match. If it still fails, tell the")
            appendLine("user exactly which step failed and what you saw on screen.")
            appendLine()
            appendLine("NAVIGATION: use go_back to dismiss dialogs / return to the previous screen,")
            appendLine("and go_home to exit the current app and return to the home screen.")
            appendLine()
            appendLine("LIMITATIONS: you cannot read video or image content — only text and UI elements.")
            appendLine("If the user asks 'what's in this video' you must say you cannot see video content.")
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

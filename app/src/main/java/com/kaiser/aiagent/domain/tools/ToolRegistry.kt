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
            appendLine("You can answer questions, manage files, and control apps via scripts.")
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
            appendLine("4. Be honest about your limitations. If a tool returns ERROR, tell the user.")
            appendLine()
            appendLine("YOUTUBE (v0.7 script-first architecture):")
            appendLine("When the user wants to play, watch, or search for something on YouTube:")
            appendLine("  1. Extract the search query from their request.")
            appendLine("     'play cocomelon on youtube' → query='cocomelon'")
            appendLine("     'watch BBS racing wheels' → query='BBS racing wheels'")
            appendLine("  2. Call youtube_search(query) — this opens YouTube, types the query,")
            appendLine("     submits the search, and returns up to 10 structured results with")
            appendLine("     title, channel, views, uploaded, and index.")
            appendLine("  3. Look at the results. Decide which one to play:")
            appendLine("     - Prefer official channels (high subscriber count, established)")
            appendLine("     - Prefer higher view counts for popular content")
            appendLine("     - Prefer newer uploads for news/tutorials")
            appendLine("     - If two results are equally good, pick randomly between them")
            appendLine("  4. If the user said 'play' or 'watch': call youtube_play(index)")
            appendLine("     If the user said 'search' or 'find': just list the results")
            appendLine("  5. After youtube_play returns, tell the user what's playing.")
            appendLine()
            appendLine("WORKED EXAMPLE:")
            appendLine("  User: play cocomelon on youtube")
            appendLine("  You call: youtube_search {\"query\":\"cocomelon\"}")
            appendLine("    → result: {\"query\":\"cocomelon\",\"result_count\":10,")
            appendLine("               \"results\":\"index=0 title='Cocomelon Nursery Rhymes' ")
            appendLine("               channel='Cocomelon' views='80M views' uploaded='1 year ago'\"}")
            appendLine("  You call: youtube_play {\"index\":0}")
            appendLine("    → result: 'Playing: Cocomelon Nursery Rhymes by Cocomelon (80M views, 1 year ago)'")
            appendLine("  You answer: 'Playing Cocomelon Nursery Rhymes on YouTube — the official")
            appendLine("               channel with 80M views.'")
            appendLine()
            appendLine("IF youtube_search returns ERROR:")
            appendLine("- If YouTube isn't installed → tell the user")
            appendLine("- If search box wasn't found → tell the user YouTube's UI may have changed")
            appendLine("- If 0 results parsed → the parser couldn't find results. Tell the user")
            appendLine("  what happened. Do NOT make up results.")
            appendLine()
            appendLine("IF youtube_play returns ERROR:")
            appendLine("- 'No search result at index N' → you called play before search, or with a")
            appendLine("  bad index. Call youtube_search first.")
            appendLine("- 'YouTube is no longer in the foreground' → the user switched apps.")
            appendLine("  Ask them to come back to YouTube, then retry.")
            appendLine()
            appendLine("OTHER APPS:")
            appendLine("- open_app still works for launching apps (Settings, Chrome, etc.)")
            appendLine("- YouTube is the only app with full script support right now")
            appendLine("- For other apps, tell the user what you can do (open the app) and what")
            appendLine("  you can't (interact with it). Be honest.")
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

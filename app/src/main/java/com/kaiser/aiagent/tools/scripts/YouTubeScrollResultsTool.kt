package com.kaiser.aiagent.tools.scripts

import com.kaiser.aiagent.accessibility.AgentAccessibilityController
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import com.kaiser.aiagent.scripts.YouTubeScriptState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.7: Scrolls down on YouTube search results and re-parses.
 *
 * After scrolling, the old results scroll off screen and new ones
 * appear. This tool re-parses the screen and updates the cached
 * results. Indices restart at 0 (the new top-of-screen result).
 */
class YouTubeScrollResultsTool : AgentTool {
    override val name = "youtube_scroll_results"
    override val description =
        "Scrolls down on the YouTube search results page and returns the new results. " +
            "Use when the user wants to see more options, or when the first page of results " +
            "didn't have what they wanted. Indices restart at 0 after scrolling."
    override val argumentsSchema = "{}"
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(arguments: String): ToolResult {
        // Verify we're still on YouTube
        val pkg = AgentAccessibilityController.runTyped("check_window") { svc ->
            svc.activeWindowPackage()
        }
        if (pkg != "com.google.android.youtube") {
            return ToolResult(false, "",
                "YouTube is not in the foreground. Call youtube_search first.")
        }

        // Scroll down
        val scrollResult = AgentAccessibilityController.run("scroll_down") { svc ->
            svc.scroll("down")
        }
        if (scrollResult.startsWith("ERROR")) {
            return ToolResult(false, "", "Scroll failed. $scrollResult")
        }

        // Wait for new results to load (poll, up to 8s)
        Thread.sleep(1000)  // brief pause for scroll animation

        val results = AgentAccessibilityController.runTyped("parse_after_scroll") { svc ->
            svc.pollForCondition(8_000, 500) {
                svc.parseYouTubeResults().isNotEmpty()
            }
            svc.parseYouTubeResults()
        } ?: emptyList()

        if (results.isEmpty()) {
            return ToolResult(false, "",
                "Scrolled but couldn't parse any new results. The page may have reached the end.")
        }

        // Update cached state
        YouTubeScriptState.update(results, YouTubeScriptState.lastQuery)

        val resultsJson = results.joinToString("\n") { it.toAiString() }
        return ToolResult(
            success = true,
            data = "Scrolled. New results:\n$resultsJson",
            error = null,
            metadata = mapOf("result_count" to results.size.toString())
        )
    }
}

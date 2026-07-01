package com.kaiser.aiagent.tools.scripts

import com.kaiser.aiagent.accessibility.AgentAccessibilityController
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import com.kaiser.aiagent.scripts.YouTubeScriptState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.7: Plays a YouTube search result by index.
 *
 * Looks up the result's screen bounds from [YouTubeScriptState]
 * (populated by a prior youtube_search call) and taps the exact
 * centre coordinates. No text matching — no "tapped Voice search
 * instead of Search" bug.
 *
 * After tapping, polls for the video page to load (up to 10s — handles
 * slow internet), then returns "Playing: <title>" to the AI.
 */
class YouTubePlayTool : AgentTool {
    override val name = "youtube_play"
    override val description =
        "Plays a YouTube video by tapping the search result at the given index. " +
            "You MUST call youtube_search first — this tool uses the bounds captured " +
            "during the search. Returns 'Playing: <title>' on success."
    override val argumentsSchema = """{"index":"<0-based result index>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    private val json = Json { ignoreUnknownKeys = true }

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("index", buildJsonObject {
                put("type", "integer")
                put("description", "0-based index of the search result to play (from the youtube_search results)")
                put("minimum", 0)
            })
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("index"))))
    }

    override suspend fun execute(arguments: String): ToolResult {
        val obj = try { json.parseToJsonElement(arguments.ifBlank{"{}"}) as? JsonObject } catch(e:Exception){null}
        val index = (obj?.get("index") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, "", "Missing 'index' argument.")

        val result = YouTubeScriptState.get(index)
            ?: return ToolResult(false, "",
                "No search result at index $index. Call youtube_search first, then " +
                    "youtube_play with a valid index (0 to ${YouTubeScriptState.lastResults.size - 1}).")

        // Verify we're still on YouTube
        val pkg = AgentAccessibilityController.runTyped("check_window") { svc ->
            svc.activeWindowPackage()
        }
        if (pkg != "com.google.android.youtube") {
            return ToolResult(false, "",
                "YouTube is no longer in the foreground (current: $pkg). Call youtube_search again.")
        }

        // Tap the centre of the result's bounds
        val bounds = result.bounds
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f

        val tapResult = AgentAccessibilityController.run("tap_result") { svc ->
            // Use dispatchTap directly with the exact coordinates
            val path = android.graphics.Path().apply { moveTo(cx, cy) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            if (svc.dispatchGesturePublic(gesture)) "OK" else "ERROR: gesture failed"
        }
        if (tapResult.startsWith("ERROR")) {
            return ToolResult(false, "", "Failed to tap result $index. $tapResult")
        }

        // Poll for video page to load (up to 10s — handles slow internet)
        // We detect this by checking if the window changed (new activity)
        // or if interactive element count dropped (video page has fewer
        // clickable elements than search results).
        AgentAccessibilityController.runTyped("wait_video") { svc ->
            svc.pollForCondition(10_000, 500) {
                // The video page usually has a pause button or a progress bar
                // We just check that the screen changed somehow
                val count = svc.interactiveElementCount()
                count in 1..15  // video page has fewer elements than search results
            }
            Unit
        }

        // Give it a moment for playback to actually start
        Thread.sleep(2000)

        return ToolResult(
            success = true,
            data = "Playing: ${result.title} by ${result.channel} " +
                "(${result.views.ifBlank {"unknown views"}}, ${result.uploaded.ifBlank {"unknown date"}})",
            error = null,
            metadata = mapOf(
                "index" to index.toString(),
                "title" to result.title,
                "channel" to result.channel
            )
        )
    }
}

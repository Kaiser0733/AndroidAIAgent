package com.kaiser.aiagent.tools.scripts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.kaiser.aiagent.accessibility.AgentAccessibilityController
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import com.kaiser.aiagent.domain.tools.stringParam
import com.kaiser.aiagent.floating.FloatingChatService
import com.kaiser.aiagent.scripts.YouTubeScriptState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.7: YouTube search script.
 *
 * This is NOT a raw accessibility tool — it's a high-level script that
 * runs the ENTIRE search flow deterministically:
 *   1. Open YouTube
 *   2. Wait for YouTube window (adaptive polling, up to 12s)
 *   3. Tap the search icon (smart match — "Search" not "Voice search")
 *   4. Wait 1s for search panel to animate in
 *   5. Type the query
 *   6. Press enter (submits the search)
 *   7. Poll for results to appear (up to 15s — handles slow internet)
 *   8. Parse results into structured data (title, channel, views, uploaded, bounds)
 *   9. Cache results in YouTubeScriptState for youtube_play
 *  10. Return structured data to the AI
 *
 * The AI then looks at the results and decides which video to play
 * (calling youtube_play with the chosen index).
 *
 * This replaces 6-7 raw tool calls + 6-7 AI calls with 1 script call.
 */
class YouTubeSearchTool(private val context: Context) : AgentTool {
    override val name = "youtube_search"
    override val description =
        "Opens YouTube, searches for the given query, and returns up to 10 structured results. " +
            "Each result has: index, title, channel, views, uploaded, and flags (LIVE/PLAYLIST). " +
            "After seeing the results, call youtube_play(index) to play one, or tell the user what " +
            "you found. Handles slow internet by polling for results (up to 15s)."
    override val argumentsSchema = """{"query":"<search query>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    private val json = Json { ignoreUnknownKeys = true }

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", stringParam("What to search for on YouTube (e.g. 'cocomelon', 'BBS racing')"))
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
    }

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = try { json.parseToJsonElement(arguments.ifBlank{"{}"}) as? JsonObject } catch(e:Exception){null}
        val query = (obj?.get("query") as? JsonPrimitive)?.content
            ?: return@withContext ToolResult(false, "", "Missing 'query' argument.")

        // Step 1: Open YouTube
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.google.android.youtube")
        if (launchIntent == null) {
            return@withContext ToolResult(false, "",
                "YouTube is not installed on this device.")
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Start floating overlay so the user can see what's happening
        FloatingChatService.startIfPermitted(context)
        Thread.sleep(200)

        context.startActivity(launchIntent)

        // Step 2: Wait for YouTube window to appear (adaptive — up to 12s)
        val windowReady = AgentAccessibilityController.runTyped("youtube_open") { svc ->
            svc.pollForCondition(12_000, 500) {
                svc.activeWindowPackage() == "com.google.android.youtube" &&
                svc.interactiveElementCount() >= 3
            }
        } ?: false

        if (!windowReady) {
            return@withContext ToolResult(false, "",
                "YouTube didn't open within 12 seconds. It may not be installed or the device is too slow.")
        }

        // Step 3: Tap the search icon
        val tapResult = AgentAccessibilityController.run("tap_search") { svc ->
            svc.tapText("Search", -1)
        }
        if (tapResult.startsWith("ERROR")) {
            // Fallback: try "Search YouTube"
            val fallback = AgentAccessibilityController.run("tap_search_fallback") { svc ->
                svc.tapText("Search YouTube", -1)
            }
            if (fallback.startsWith("ERROR")) {
                return@withContext ToolResult(false, "",
                    "Couldn't find the search button on YouTube's home screen. $tapResult")
            }
        }

        // Step 4: Wait for search panel + text field to appear
        Thread.sleep(1000)

        // Step 5: Type the query
        val typeResult = AgentAccessibilityController.run("type_query") { svc ->
            svc.typeText(query)
        }
        if (typeResult.startsWith("ERROR")) {
            return@withContext ToolResult(false, "",
                "Couldn't type into YouTube's search box. $typeResult")
        }

        // Step 6: Submit the search.
        // v0.7.2: YouTube has NO submit button — only the keyboard's
        // enter key or tapping an autocomplete suggestion works.
        // submitYouTubeSearch tries:
        //   1. ACTION_IME_ENTER (with verification)
        //   2. Tap first autocomplete suggestion (most reliable)
        //   3. ACTION_CLICK on EditText (last resort)
        // NEVER taps voice/mic.
        val submitResult = AgentAccessibilityController.run("submit_search") { svc ->
            svc.submitYouTubeSearch()
        }
        if (submitResult.startsWith("ERROR")) {
            return@withContext ToolResult(false, "",
                "Typed '$query' but couldn't submit the search. $submitResult")
        }

        // Step 7: Poll for results to appear (up to 15s — handles slow internet)
        val results = AgentAccessibilityController.runTyped("parse_results") { svc ->
            svc.pollForCondition(15_000, 500) {
                svc.parseYouTubeResults().isNotEmpty()
            }
            svc.parseYouTubeResults()
        } ?: emptyList()

        if (results.isEmpty()) {
            // Fallback: return raw screen so the AI knows what happened
            val rawScreen = AgentAccessibilityController.run("read_screen_fallback") { svc ->
                svc.readScreen()
            }
            return@withContext ToolResult(
                success = false,
                data = rawScreen,
                error = "Search completed but couldn't parse any results. " +
                    "YouTube may have changed their UI. Raw screen:\n$rawScreen"
            )
        }

        // Step 9: Cache results
        YouTubeScriptState.update(results, query)

        // Step 10: Return structured data to the AI
        val resultsJson = results.joinToString("\n") { it.toAiString() }
        val data = buildJsonObject {
            put("query", query)
            put("result_count", results.size)
            put("results", resultsJson)
            put("hint", "Call youtube_play(index) to play one of these, or tell the user what you found.")
        }.toString()

        ToolResult(success = true, data = data, error = null,
            metadata = mapOf("result_count" to results.size.toString()))
    }
}

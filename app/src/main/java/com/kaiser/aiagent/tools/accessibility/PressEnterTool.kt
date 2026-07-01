package com.kaiser.aiagent.tools.accessibility

import com.kaiser.aiagent.accessibility.AgentAccessibilityController
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.6.6: Submits the currently-focused input field.
 *
 * This is the correct way to "press Enter" on a search box after
 * type_text. type_text uses ACTION_SET_TEXT which bypasses the
 * keyboard, so the IME's "Search/Go/Submit" button never gets
 * pressed. This tool triggers the IME action via:
 *   1. ACTION_IME_ENTER on the focused EditText (API 30+)
 *   2. ACTION_CLICK on the focused EditText (fallback)
 *   3. Tap any visible Search/Go/Submit/Done button (last resort)
 *
 * No arguments.
 *
 * Example chain for "Open YouTube and search for BBS Racing":
 *   open_app YouTube
 *   read_screen
 *   tap_text "Search"
 *   type_text "BBS Racing"
 *   press_enter           ← THIS submits the search
 *   read_screen           ← verify results
 *   answer
 */
class PressEnterTool : AgentTool {
    override val name = "press_enter"
    override val description =
        "Submits the currently-focused input field. This is how you 'press Enter' on a " +
            "search box after type_text. type_text only fills the field — it does NOT submit. " +
            "Always call press_enter after type_text in a search box to actually run the search. " +
            "Tries ACTION_IME_ENTER on the focused field first, then ACTION_CLICK, then looks " +
            "for a Search/Go/Submit button on screen."
    override val argumentsSchema = "{}"
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(arguments: String): ToolResult {
        val out = AgentAccessibilityController.run("press_enter") { svc -> svc.pressEnter() }
        return ToolResult(
            success = out.startsWith("OK"),
            data = out,
            error = if (out.startsWith("ERROR")) out else null
        )
    }
}

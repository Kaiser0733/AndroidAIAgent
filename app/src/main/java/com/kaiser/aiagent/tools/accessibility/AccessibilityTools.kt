package com.kaiser.aiagent.tools.accessibility

import com.kaiser.aiagent.accessibility.AgentAccessibilityController
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import com.kaiser.aiagent.domain.tools.stringParam
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.6.2: Reads the visible UI tree of whatever app is currently in
 * the foreground. The result is a newline-separated list of:
 *   label|className|left,top,right,bottom[|clickable][|editable]
 *
 * The model uses this to discover buttons, search boxes, and links
 * it can interact with via tap_text / type_text.
 *
 * No arguments.
 */
class ReadScreenTool : AgentTool {
    override val name = "read_screen"
    override val description =
        "Reads the currently visible screen. Returns a list of interactive elements " +
            "(buttons, links, text fields) with their text, position, and flags. " +
            "Call this AFTER open_app to see what is on screen, then tap_text or " +
            "type_text to interact. Caps at 200 elements."
    override val argumentsSchema = "{}"
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(arguments: String): ToolResult {
        val out = AgentAccessibilityController.run("read_screen") { svc ->
            svc.readScreen()
        }
        val ok = out.startsWith("OK") || !out.startsWith("ERROR")
        return ToolResult(
            success = !out.startsWith("ERROR"),
            data = out,
            error = if (out.startsWith("ERROR")) out else null
        )
    }
}

/**
 * Taps the first on-screen element whose text or content description
 * matches [text] (case-insensitive substring match).
 *
 * Example: tap_text "Search" — taps the YouTube search icon.
 */
class TapTextTool : AgentTool {
    override val name = "tap_text"
    override val description =
        "Taps an element on the current screen by matching its text. " +
            "Case-insensitive substring match. Use after read_screen to find the " +
            "exact label. Examples: tap the Search button, tap a video title."
    override val argumentsSchema = """{"text":"<button text or label>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("text", stringParam("Text on the element to tap (case-insensitive substring)"))
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("text"))))
    }

    override suspend fun execute(arguments: String): ToolResult {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        } catch (e: Exception) { null }
        val text = (obj?.get("text") as? JsonPrimitive)?.content
            ?: return ToolResult(false, "", "Missing 'text' argument.")
        val out = AgentAccessibilityController.run("tap_text") { svc -> svc.tapText(text) }
        return ToolResult(
            success = out.startsWith("OK"),
            data = out,
            error = if (out.startsWith("ERROR")) out else null
        )
    }
}

/**
 * Types [text] into the currently-focused input field. If no field
 * is focused, finds the first EditText on screen and focuses it.
 *
 * Example: type_text "BBS Racing" — types into the YouTube search box
 * (after tap_text "Search" focuses it).
 */
class TypeTextTool : AgentTool {
    override val name = "type_text"
    override val description =
        "Types text into the focused input field. If no field is focused, finds " +
            "the first editable field on screen and focuses it. Use after tap_text " +
            "on a search box to enter a query."
    override val argumentsSchema = """{"text":"<text to type>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("text", stringParam("Text to type into the focused input field"))
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("text"))))
    }

    override suspend fun execute(arguments: String): ToolResult {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        } catch (e: Exception) { null }
        val text = (obj?.get("text") as? JsonPrimitive)?.content
            ?: return ToolResult(false, "", "Missing 'text' argument.")
        val out = AgentAccessibilityController.run("type_text") { svc -> svc.typeText(text) }
        return ToolResult(
            success = out.startsWith("OK"),
            data = out,
            error = if (out.startsWith("ERROR")) out else null
        )
    }
}

/**
 * Scrolls the screen in a direction. Implemented as a swipe gesture.
 * Used to reveal more content (e.g. scroll down to see more YouTube
 * results, scroll up to refresh).
 *
 * Argument: direction = "up" | "down" | "left" | "right"
 */
class ScrollTool : AgentTool {
    override val name = "scroll"
    override val description =
        "Scrolls the screen in a direction (up, down, left, or right). " +
            "Implemented as a swipe gesture. Use to reveal more content."
    override val argumentsSchema = """{"direction":"<up|down|left|right>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("direction", stringParam("up, down, left, or right"))
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("direction"))))
    }

    override suspend fun execute(arguments: String): ToolResult {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        } catch (e: Exception) { null }
        val dir = (obj?.get("direction") as? JsonPrimitive)?.content?.lowercase()
            ?: return ToolResult(false, "", "Missing 'direction' argument.")
        val out = AgentAccessibilityController.run("scroll") { svc -> svc.scroll(dir) }
        return ToolResult(
            success = out.startsWith("OK"),
            data = out,
            error = if (out.startsWith("ERROR")) out else null
        )
    }
}

/** Presses the hardware BACK button. */
class GoBackTool : AgentTool {
    override val name = "go_back"
    override val description =
        "Presses the BACK button. Use to dismiss dialogs, return to the previous " +
            "screen, or exit a search."
    override val argumentsSchema = "{}"
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(arguments: String): ToolResult {
        val out = AgentAccessibilityController.run("go_back") { svc -> svc.goBack() }
        return ToolResult(
            success = out.startsWith("OK"),
            data = out,
            error = if (out.startsWith("ERROR")) out else null
        )
    }
}

/** Presses the hardware HOME button. */
class GoHomeTool : AgentTool {
    override val name = "go_home"
    override val description =
        "Presses the HOME button. Exits the current app and returns to the home screen."
    override val argumentsSchema = "{}"
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(arguments: String): ToolResult {
        val out = AgentAccessibilityController.run("go_home") { svc -> svc.goHome() }
        return ToolResult(
            success = out.startsWith("OK"),
            data = out,
            error = if (out.startsWith("ERROR")) out else null
        )
    }
}

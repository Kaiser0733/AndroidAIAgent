package com.kaiser.aiagent.tools.accessibility

import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import com.kaiser.aiagent.domain.tools.stringParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.6.4: Pauses the agent for [seconds] seconds. Use between UI steps
 * when the target app needs time to load (e.g. after open_app, after
 * tap_text that opens a new screen, after type_text that triggers a
 * search).
 *
 * Capped at 10 seconds per call to prevent runaway loops.
 *
 * Examples:
 *   wait_seconds 2  — give YouTube 2s to load after open_app
 *   wait_seconds 1  — let the search results appear after submit
 */
class WaitSecondsTool : AgentTool {
    override val name = "wait_seconds"
    override val description =
        "Pauses the agent for the given number of seconds (1-10). Use after open_app " +
            "if read_screen shows a splash or empty screen, after tap_text that opens " +
            "a new screen, or after type_text that triggers a search — anything where " +
            "the target app needs a moment to update its UI before the next read_screen."
    override val argumentsSchema = """{"seconds":"<1-10>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("seconds", buildJsonObject {
                put("type", "integer")
                put("description", "Number of seconds to wait (1-10).")
                put("minimum", 1)
                put("maximum", 10)
            })
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("seconds"))))
    }

    override suspend fun execute(arguments: String): ToolResult {
        val obj = try {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        } catch (e: Exception) { null }
        val secs = (obj?.get("seconds") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, "", "Missing 'seconds' argument.")
        val clamped = secs.coerceIn(1, 10)
        kotlinx.coroutines.delay(clamped * 1000L)
        return ToolResult(
            success = true,
            data = "OK waited ${clamped}s",
            error = null
        )
    }
}

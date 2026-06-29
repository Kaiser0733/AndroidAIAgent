package com.kaiser.aiagent.tools.demo

import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Demo tool — returns the current local time in ISO 8601 format.
 *
 * Read-only, side-effect-free, safe to expose to the model.
 *
 * v0.4: migrated to the new [ToolResult] shape.
 */
class GetTimeTool : AgentTool {
    override val name: String = "get_time"
    override val description: String =
        "Returns the current local time on the device in ISO 8601 format " +
            "(e.g. 2026-06-27T15:30:45+05:30). Takes no arguments."
    override val permissionLevel: ToolPermissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val now = Date()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(now)
        val result = JsonObject(
            mapOf(
                "iso" to JsonPrimitive(iso),
                "timezone" to JsonPrimitive(TimeZone.getDefault().id),
                "epoch_ms" to JsonPrimitive(now.time)
            )
        )
        val data = Json.encodeToString(JsonObject.serializer(), result)
        return ToolResult(
            success = true,
            data = data,
            metadata = mapOf("timezone" to (TimeZone.getDefault().id ?: "UTC"))
        )
    }
}

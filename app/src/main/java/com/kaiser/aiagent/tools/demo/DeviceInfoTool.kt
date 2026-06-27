package com.kaiser.aiagent.tools.demo

import android.os.Build
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Demo tool — returns the device model and Android version.
 *
 * Read-only.
 *
 * v0.4: migrated to the new [ToolResult] shape.
 */
class DeviceInfoTool : AgentTool {
    override val name: String = "device_info"
    override val description: String =
        "Returns the device manufacturer, model, Android version, and SDK " +
            "level. Takes no arguments."
    override val permissionLevel: ToolPermissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val result = JsonObject(
            mapOf(
                "manufacturer" to JsonPrimitive(Build.MANUFACTURER),
                "model" to JsonPrimitive(Build.MODEL),
                "android_version" to JsonPrimitive(Build.VERSION.RELEASE),
                "sdk_level" to JsonPrimitive(Build.VERSION.SDK_INT),
                "abis" to JsonPrimitive(Build.SUPPORTED_ABIS.joinToString(","))
            )
        )
        return ToolResult(
            success = true,
            data = Json.encodeToString(JsonObject.serializer(), result),
            metadata = mapOf("android_version" to (Build.VERSION.RELEASE ?: "unknown"))
        )
    }
}

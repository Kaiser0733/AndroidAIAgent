package com.kaiser.aiagent.tools.demo

import android.os.Build
import com.kaiser.aiagent.domain.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Demo tool — returns the device model and Android version.
 *
 * Read-only.
 */
class DeviceInfoTool : AgentTool {
    override val name: String = "device_info"
    override val description: String =
        "Returns the device manufacturer, model, Android version, and SDK " +
            "level. Takes no arguments."

    override suspend fun execute(arguments: String): String {
        val result = JsonObject(
            mapOf(
                "manufacturer" to JsonPrimitive(Build.MANUFACTURER),
                "model" to JsonPrimitive(Build.MODEL),
                "android_version" to JsonPrimitive(Build.VERSION.RELEASE),
                "sdk_level" to JsonPrimitive(Build.VERSION.SDK_INT),
                "abis" to JsonPrimitive(Build.SUPPORTED_ABIS.joinToString(","))
            )
        )
        return Json.encodeToString(JsonObject.serializer(), result)
    }
}

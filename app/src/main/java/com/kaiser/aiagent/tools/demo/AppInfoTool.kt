package com.kaiser.aiagent.tools.demo

import com.kaiser.aiagent.BuildConfig
import com.kaiser.aiagent.domain.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Demo tool — returns the app version and build number.
 *
 * Read-only.
 */
class AppInfoTool : AgentTool {
    override val name: String = "app_info"
    override val description: String =
        "Returns the app's version name, version code, build type, and " +
            "application ID. Takes no arguments."

    override suspend fun execute(arguments: String): String {
        val result = JsonObject(
            mapOf(
                "app_name" to JsonPrimitive("Android AI Agent"),
                "version_name" to JsonPrimitive(BuildConfig.VERSION_NAME),
                "version_code" to JsonPrimitive(BuildConfig.VERSION_CODE),
                "build_type" to JsonPrimitive(BuildConfig.BUILD_TYPE),
                "application_id" to JsonPrimitive(BuildConfig.APPLICATION_ID)
            )
        )
        return Json.encodeToString(JsonObject.serializer(), result)
    }
}

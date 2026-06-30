package com.kaiser.aiagent.domain.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A single capability the agent can invoke via native function calling.
 *
 * v0.6: added [parametersJsonSchema] for native function calling.
 * The model receives this schema and knows exactly what arguments to pass.
 */
interface AgentTool {
    val name: String
    val description: String
    val argumentsSchema: String
        get() = "{}"

    val permissionLevel: ToolPermissionLevel
        get() = ToolPermissionLevel.SAFE

    /**
     * v0.6: Returns a proper JSON Schema object for native function calling.
     * Override this to provide a detailed schema. Default: empty object schema.
     */
    fun parametersJsonSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    suspend fun execute(arguments: String): ToolResult
}

/** Helper to build a string parameter schema. */
fun stringParam(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

/** Helper to build a required string parameter. */
fun requiredStringParam(description: String): JsonObject = stringParam(description)

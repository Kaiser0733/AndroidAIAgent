package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Shared helpers for file tools. */
abstract class BaseFileTool(protected val storage: StorageRepository) : AgentTool {
    protected val json = Json { ignoreUnknownKeys = true; isLenient = true }

    protected fun parseArgs(arguments: String): JsonObject = try {
        json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    protected fun JsonObject.optString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    protected fun errorResult(msg: String): ToolResult = ToolResult(
        success = false, data = "", error = msg
    )

    protected fun successResult(jsonStr: String, metadata: Map<String, String> = emptyMap()): ToolResult =
        ToolResult(success = true, data = jsonStr, metadata = metadata)
}

/** Helper to build a single FileEntry JSON object. */
fun StorageRepository.FileEntry.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("path", absolutePath)
    put("is_directory", isDirectory)
    put("size_bytes", sizeBytes)
    put("size_human", StorageRepository.formatSize(sizeBytes))
    put("modified_ms", lastModifiedMs)
    put("modified_human", StorageRepository.formatDate(lastModifiedMs))
    put("mime_type", mimeType ?: "application/octet-stream")
}

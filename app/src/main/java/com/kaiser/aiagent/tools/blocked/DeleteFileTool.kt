package com.kaiser.aiagent.tools.blocked

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * v0.5.16: Changed from BLOCKED to SAFE. The user explicitly requested
 * deletion capability. The tool now actually deletes files.
 *
 * Safety: refuses to delete system directories (/system, /proc, /dev,
 * /data, etc.) and refuses path traversal (..).
 */
class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description =
        "Deletes a file or empty folder at the given path. " +
            "Use this when the user asks to 'delete', 'remove', or 'erase' a file. " +
            "Refuses to delete system directories."
    override val argumentsSchema = """{"path":"<absolute path>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    private val forbiddenPaths = listOf(
        "/system", "/proc", "/dev", "/data", "/sys",
        "/vendor", "/sbin", "/etc", "/root"
    )

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        // Parse the path from arguments
        val path = try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(arguments.ifBlank { "{}" }) as? kotlinx.serialization.json.JsonObject
            (obj?.get("path") as? kotlinx.serialization.json.JsonPrimitive)?.content
        } catch (e: Exception) { null } ?: return@withContext ToolResult(
            success = false, data = "", error = "Missing 'path' argument."
        )

        if (path.isBlank()) return@withContext ToolResult(
            success = false, data = "", error = "Missing 'path' argument."
        )

        // Safety: refuse system paths
        val normalized = File(path).canonicalPath
        for (forbidden in forbiddenPaths) {
            if (normalized.startsWith(forbidden)) return@withContext ToolResult(
                success = false, data = "",
                error = "Refusing to delete system path: $path"
            )
        }

        // Safety: refuse path traversal
        if (path.contains("..")) return@withContext ToolResult(
            success = false, data = "",
            error = "Path traversal not allowed."
        )

        val file = File(path)
        if (!file.exists()) return@withContext ToolResult(
            success = false, data = "",
            error = "File not found: $path"
        )

        val deleted = try {
            if (file.isDirectory) {
                // Only delete empty directories
                file.delete()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            return@withContext ToolResult(
                success = false, data = "",
                error = "Failed to delete: ${e.message}"
            )
        }

        if (deleted) {
            val obj = buildJsonObject {
                put("path", path)
                put("deleted", true)
            }
            ToolResult(success = true, data = obj.toString(), metadata = mapOf("path" to path))
        } else {
            ToolResult(
                success = false, data = "",
                error = "Could not delete '$path'. It may be a non-empty directory or the app may not have permission."
            )
        }
    }
}

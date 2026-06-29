package com.kaiser.aiagent.tools.blocked

import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * v0.5.16: Changed from BLOCKED to SAFE. Renames a file or folder.
 */
class RenameFileTool : AgentTool {
    override val name = "rename_file"
    override val description =
        "Renames a file or folder. Use this when the user asks to 'rename' a file."
    override val argumentsSchema = """{"path":"<current path>","new_name":"<new name>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = try {
            json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        } catch (e: Exception) { null } ?: return@withContext ToolResult(
            success = false, data = "", error = "Invalid arguments."
        )

        val path = (obj["path"] as? JsonPrimitive)?.content
        val newName = (obj["new_name"] as? JsonPrimitive)?.content
        if (path.isNullOrBlank() || newName.isNullOrBlank()) return@withContext ToolResult(
            success = false, data = "", error = "Missing 'path' or 'new_name' argument."
        )

        val source = File(path)
        if (!source.exists()) return@withContext ToolResult(
            success = false, data = "", error = "File not found: $path"
        )

        val dest = File(source.parentFile, newName)
        val renamed = try { source.renameTo(dest) } catch (e: Exception) { false }
        if (renamed) {
            ToolResult(
                success = true,
                data = buildJsonObject {
                    put("old_path", path)
                    put("new_path", dest.absolutePath)
                    put("renamed", true)
                }.toString(),
                metadata = mapOf("path" to path, "new_name" to newName)
            )
        } else {
            ToolResult(success = false, data = "", error = "Could not rename '$path' to '$newName'.")
        }
    }
}

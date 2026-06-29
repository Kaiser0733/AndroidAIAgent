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
 * v0.5.16: Changed from BLOCKED to SAFE. Moves/renames a file or folder.
 */
class MoveFileTool : AgentTool {
    override val name = "move_file"
    override val description =
        "Moves or renames a file/folder from one path to another. " +
            "Use this when the user asks to 'move', 'rename', or 'relocate' a file."
    override val argumentsSchema = """{"from":"<source path>","to":"<destination path>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = try {
            json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        } catch (e: Exception) { null } ?: return@withContext ToolResult(
            success = false, data = "", error = "Invalid arguments."
        )

        val from = (obj["from"] as? JsonPrimitive)?.content
        val to = (obj["to"] as? JsonPrimitive)?.content
        if (from.isNullOrBlank() || to.isNullOrBlank()) return@withContext ToolResult(
            success = false, data = "", error = "Missing 'from' or 'to' argument."
        )

        val source = File(from)
        if (!source.exists()) return@withContext ToolResult(
            success = false, data = "", error = "Source not found: $from"
        )

        val dest = File(to)
        dest.parentFile?.mkdirs()

        val moved = try { source.renameTo(dest) } catch (e: Exception) { false }
        if (moved) {
            ToolResult(
                success = true,
                data = buildJsonObject { put("from", from); put("to", to); put("moved", true) }.toString(),
                metadata = mapOf("from" to from, "to" to to)
            )
        } else {
            ToolResult(success = false, data = "", error = "Could not move '$from' to '$to'.")
        }
    }
}

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

class RenameFileTool : AgentTool {
    override val name = "rename_file"
    override val description = "Renames a file or folder. Use when user asks to rename."
    override val argumentsSchema = """{"path":"<current>","new_name":"<new name>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = try { json.parseToJsonElement(arguments.ifBlank{"{}"}) as? JsonObject } catch(e:Exception){null}
        val rawPath = (obj?.get("path") as? JsonPrimitive)?.content
        val rawNewName = (obj?.get("new_name") as? JsonPrimitive)?.content
        if (rawPath.isNullOrBlank() || rawNewName.isNullOrBlank()) return@withContext ToolResult(false, "", "Missing 'path' or 'new_name'.")
        val path: String = rawPath
        val newName: String = rawNewName
        val src = File(path)
        if (!src.exists()) return@withContext ToolResult(false, "", "File not found: $path")
        val dest = File(src.parentFile, newName)
        val renamed = try { src.renameTo(dest) } catch(e:Exception) { false }
        if (renamed) {
            ToolResult(true, buildJsonObject{put("old_path",path);put("new_path",dest.absolutePath);put("renamed",true)}.toString(), null, mapOf("path" to path))
        } else {
            ToolResult(false, "", "Could not rename '$path' to '$newName'.")
        }
    }
}

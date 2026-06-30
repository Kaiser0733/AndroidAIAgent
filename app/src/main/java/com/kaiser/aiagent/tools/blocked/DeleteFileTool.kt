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

class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description = "Deletes a file or empty folder. Use when user asks to delete/remove/erase."
    override val argumentsSchema = """{"path":"<absolute path>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE
    private val json = Json { ignoreUnknownKeys = true }
    private val forbidden = listOf("/system","/proc","/dev","/data","/sys","/vendor","/sbin","/etc","/root")

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = try { json.parseToJsonElement(arguments.ifBlank{"{}"}) as? JsonObject } catch(e:Exception){null}
        val rawPath = (obj?.get("path") as? JsonPrimitive)?.content
        if (rawPath.isNullOrBlank()) return@withContext ToolResult(false, "", "Missing 'path' argument.")
        val path: String = rawPath
        val file = File(path)
        if (!file.exists()) return@withContext ToolResult(false, "", "File not found: $path")
        val canonical = file.canonicalPath
        for (f in forbidden) if (canonical.startsWith(f)) return@withContext ToolResult(false, "", "Refusing to delete system path.")
        val deleted = try { file.delete() } catch(e:Exception) { false }
        if (deleted) {
            ToolResult(true, buildJsonObject{put("path",path);put("deleted",true)}.toString(), null, mapOf("path" to path))
        } else {
            ToolResult(false, "", "Could not delete '$path'. May be non-empty directory.")
        }
    }
}

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

class MoveFileTool : AgentTool {
    override val name = "move_file"
    override val description = "Moves/renames a file or folder. Use when user asks to move/relocate."
    override val argumentsSchema = """{"from":"<source>","to":"<destination>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = try { json.parseToJsonElement(arguments.ifBlank{"{}"}) as? JsonObject } catch(e:Exception){null}
        val rawFrom = (obj?.get("from") as? JsonPrimitive)?.content
        val rawTo = (obj?.get("to") as? JsonPrimitive)?.content
        if (rawFrom.isNullOrBlank() || rawTo.isNullOrBlank()) return@withContext ToolResult(false, "", "Missing 'from' or 'to'.")
        val from: String = rawFrom
        val to: String = rawTo
        val src = File(from)
        if (!src.exists()) return@withContext ToolResult(false, "", "Source not found: $from")
        val dest = File(to)
        dest.parentFile?.mkdirs()
        val moved = try { src.renameTo(dest) } catch(e:Exception) { false }
        if (moved) {
            ToolResult(true, buildJsonObject{put("from",from);put("to",to);put("moved",true)}.toString(), null, mapOf("from" to from, "to" to to))
        } else {
            ToolResult(false, "", "Could not move '$from' to '$to'.")
        }
    }
}

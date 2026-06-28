package com.kaiser.aiagent.tools.memory

import com.kaiser.aiagent.data.memory.MemoryRepository
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SAFE — keyword search over the user's memory store.
 *
 * v0.4 only. Returns matching memories (content + type + tags + timestamp).
 * v0.5+ will add semantic search via embeddings; v0.4 is keyword-only.
 *
 * Arguments: {"query":"physics notes"}
 *
 * Empty query returns all memories (capped at 50).
 */
class SearchMemoryTool(private val memoryRepository: MemoryRepository) : AgentTool {
    override val name = "search_memory"
    override val description =
        "Searches the user's memory store by keyword. Returns matching " +
            "memory entries (content, type, tags, timestamp). Use this " +
            "when the user asks 'do you remember...' or 'what do you know " +
            "about...'. Empty query returns all memories."
    override val argumentsSchema = """{"query":"<keyword>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(arguments: String): ToolResult {
        val query = try {
            val obj = json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
            (obj?.get("query") as? JsonPrimitive)?.content ?: ""
        } catch (e: Exception) { "" }
        val matches = memoryRepository.search(query)
        val arr = buildJsonArray {
            for (m in matches) {
                add(buildJsonObject {
                    put("id", m.id)
                    put("type", m.type)
                    put("content", m.content)
                    put("source", m.source)
                    put("created_at", m.createdAt)
                    put("tags", buildJsonArray {
                        for (t in m.tags) add(JsonPrimitive(t))
                    })
                })
            }
        }
        val resultObj = buildJsonObject {
            put("query", query)
            put("matches", arr)
            put("count", matches.size)
        }
        return ToolResult(
            success = true,
            data = resultObj.toString(),
            metadata = mapOf(
                "query" to query,
                "match_count" to matches.size.toString()
            )
        )
    }
}

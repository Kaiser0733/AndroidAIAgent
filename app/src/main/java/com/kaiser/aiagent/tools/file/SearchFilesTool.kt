package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SAFE — searches for files by name query and/or extension across all
 * accessible storage roots. Caps at 200 matches.
 *
 * Arguments:
 *   {"query":"physics","extensions":["pdf","txt"]}
 *
 * Both fields are optional:
 *  - query: case-insensitive substring match against the file name.
 *    Empty string matches all files.
 *  - extensions: list of file extensions (without dot) to filter by.
 *    Empty list matches all extensions.
 */
class SearchFilesTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "search_files"
    override val description =
        "Searches for files by name and/or extension across all accessible " +
            "storage roots (Downloads, Documents, Pictures, Music, Movies, " +
            "Internal storage). Use this when the user asks to 'find', " +
            "'search', or 'look for' files. Supports pdf, txt, md, docx, " +
            "pptx, xlsx, and image/audio/video extensions. Caps at 200 " +
            "matches — if there are more, the result is truncated."
    override val argumentsSchema =
        """{"query":"<optional substring>","extensions":["pdf","txt"]}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        val query = args.optString("query") ?: ""
        val extensions: List<String> = (args["extensions"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
        val result = storage.searchFiles(query = query, extensions = extensions)
        val matchesArr = buildJsonArray {
            for (m in result.matches) add(m.toJson())
        }
        val obj = buildJsonObject {
            put("query", result.query)
            put("extensions", buildJsonArray {
                for (e in result.extensions) add(JsonPrimitive(e))
            })
            put("matches", matchesArr)
            put("match_count", result.matches.size)
            put("truncated", result.truncated)
            put("searched_dirs", result.searchedDirs)
        }
        return ToolResult(
            success = true,
            data = obj.toString(),
            metadata = mapOf(
                "query" to query,
                "match_count" to result.matches.size.toString(),
                "truncated" to result.truncated.toString(),
                "searched_dirs" to result.searchedDirs.toString()
            )
        )
    }
}

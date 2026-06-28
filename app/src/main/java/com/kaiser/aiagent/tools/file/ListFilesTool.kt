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
 * SAFE — lists files and folders in a directory.
 *
 * Arguments: {"path": "/storage/emulated/0/Downloads"}
 */
class ListFilesTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "list_files"
    override val description =
        "Lists files and folders in the given directory. Returns names, paths, " +
            "sizes, and modification dates. Caps at 200 entries — if there are " +
            "more, the result is truncated and you should narrow the search."
    override val argumentsSchema = """{"path":"<absolute path>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        val path = args.optString("path")
        if (path.isNullOrBlank()) {
            return errorResult("Missing 'path' argument.")
        }
        val result = storage.listFiles(path)
        val hasAccess = storage.hasFullStorageAccess()
        val pathExists = java.io.File(path).exists()
        val filesArr = buildJsonArray {
            for (f in result.files) add(f.toJson())
        }
        val foldersArr = buildJsonArray {
            for (f in result.folders) add(f.toJson())
        }
        val obj = buildJsonObject {
            put("path", result.path)
            put("folders", foldersArr)
            put("files", filesArr)
            put("folder_count", result.folders.size)
            put("file_count", result.files.size)
            put("total_in_dir", result.totalInDir)
            put("truncated", result.truncated)
            put("full_storage_access", hasAccess)
            put("path_exists", pathExists)
            // v0.4.2: include diagnostic warnings so the agent can
            // tell the user what's wrong.
            if (!pathExists) {
                put("warning", "Path '$path' does not exist. Use list_storage_roots to see valid paths.")
            } else if (!hasAccess && result.totalInDir == 0) {
                put("warning", "Path exists but appears empty. The app may not have 'All files access' permission — only app-created files are visible. Tell the user to open Settings → AI Configuration → 'Grant all files access'.")
            }
        }
        return ToolResult(
            success = true,
            data = obj.toString(),
            metadata = mapOf(
                "path" to path,
                "file_count" to result.files.size.toString(),
                "folder_count" to result.folders.size.toString(),
                "truncated" to result.truncated.toString(),
                "full_storage_access" to hasAccess.toString(),
                "path_exists" to pathExists.toString()
            )
        )
    }
}

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
        val canRead = java.io.File(path).canRead()
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
            put("can_read", canRead)
            // v0.5.12: detailed diagnostics
            if (!pathExists) {
                put("warning", "Path '$path' does not exist. Use list_storage_roots to see valid paths. Common paths: /storage/emulated/0/Download, /storage/emulated/0/Documents, /storage/emulated/0/Pictures")
            } else if (!canRead) {
                put("warning", "Path exists but app cannot read it. Grant 'All files access' in Settings → AI Configuration → 'Grant all files access'.")
            } else if (!hasAccess) {
                put("warning", "App does not have 'All files access' permission. Some files may not be visible.")
            } else if (result.totalInDir == 0) {
                put("info", "Directory is empty or files are not visible due to Android scoped storage restrictions.")
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
                "path_exists" to pathExists.toString(),
                "can_read" to canRead.toString()
            )
        )
    }
}

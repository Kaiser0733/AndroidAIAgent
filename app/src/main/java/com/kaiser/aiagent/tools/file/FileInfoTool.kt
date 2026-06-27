package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SAFE — returns metadata for a single file or folder.
 *
 * Arguments: {"path": "/storage/emulated/0/Downloads/report.pdf"}
 */
class FileInfoTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "file_info"
    override val description =
        "Returns metadata for a single file or folder: name, size, MIME type, " +
            "modification date, absolute path. Use this when the user asks " +
            "about a specific file's properties."
    override val argumentsSchema = """{"path":"<absolute path>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        val path = args.optString("path")
        if (path.isNullOrBlank()) {
            return errorResult("Missing 'path' argument.")
        }
        val entry = storage.fileInfo(path)
            ?: return errorResult("File not found or not accessible: $path")
        val obj = entry.toJson()
        return ToolResult(
            success = true,
            data = obj.toString(),
            metadata = mapOf(
                "path" to path,
                "size" to StorageRepository.formatSize(entry.sizeBytes)
            )
        )
    }
}

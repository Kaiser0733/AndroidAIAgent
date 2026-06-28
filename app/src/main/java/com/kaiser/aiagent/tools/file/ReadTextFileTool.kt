package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SAFE — reads the first 100 KB of a text file as UTF-8.
 *
 * Arguments: {"path": "/storage/emulated/0/Documents/notes.txt"}
 *
 * Supports: txt, md, json, xml, csv, log, yaml, yml, tsv, ini, cfg,
 * html, htm. For other extensions (PDFs, Office docs, images) the
 * model should use file_info instead and tell the user this tool can
 * only read text.
 */
class ReadTextFileTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "read_text_file"
    override val description =
        "Reads the first 100 KB of a text file as UTF-8. Supports txt, md, " +
            "json, xml, csv, log, yaml, yml, tsv, ini, cfg, html, htm. For " +
            "binary files (PDFs, Office docs, images), use file_info instead."
    override val argumentsSchema = """{"path":"<absolute path>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        val path = args.optString("path")
        if (path.isNullOrBlank()) {
            return errorResult("Missing 'path' argument.")
        }
        // Validate extension before reading.
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext !in StorageRepository.TEXT_EXTENSIONS) {
            return errorResult(
                "Cannot read '.$ext' files with read_text_file. " +
                    "Supported extensions: ${StorageRepository.TEXT_EXTENSIONS.joinToString(", ")}. " +
                    "Use file_info for binary files."
            )
        }
        val hasAccess = storage.hasFullStorageAccess()
        val fileExists = java.io.File(path).exists()
        val canRead = java.io.File(path).canRead()
        val content = storage.readTextFile(path)
        if (content == null) {
            // v0.4.2: include detailed diagnostic info in the error.
            val diag = buildString {
                append("File not found, not readable, or empty: $path\n")
                append("Diagnostics:\n")
                append("- File exists: $fileExists\n")
                append("- App can read: $canRead\n")
                append("- All files access granted: $hasAccess\n")
                if (!hasAccess) {
                    append("\nThe app does not have 'All files access' permission. ")
                    append("Tell the user to open Settings → AI Configuration → 'Grant all files access' ")
                    append("and toggle it on, then retry.")
                } else if (fileExists && !canRead) {
                    append("\nThe file exists but the app cannot read it. This may be a permissions issue ")
                    append("with the specific file. Tell the user to check the file's permissions.")
                } else if (!fileExists) {
                    append("\nThe file does not exist at the given path. Use list_files or search_files ")
                    append("to find the correct path.")
                }
            }
            return errorResult(diag)
        }
        val obj = buildJsonObject {
            put("path", path)
            put("bytes_read", content.length)
            put("truncated", content.length >= 100 * 1024)
            put("content", content)
        }
        return ToolResult(
            success = true,
            data = obj.toString(),
            metadata = mapOf(
                "path" to path,
                "bytes_read" to content.length.toString()
            )
        )
    }
}

package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CONFIRMATION_REQUIRED — creates a new text file at [parentPath]/[name]
 * with the given [content].
 *
 * The PermissionManager suspends execution and asks the user via a
 * confirmation dialog before this tool's [execute] method runs.
 *
 * Arguments:
 *   {
 *     "path": "/storage/emulated/0/Documents",
 *     "name": "notes.txt",
 *     "content": "Physics lecture notes..."
 *   }
 *
 * Refuses to overwrite existing files. Refuses path traversal.
 */
class CreateTextFileTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "create_text_file"
    override val description =
        "Creates a new text file with the given content. REQUIRES USER " +
            "CONFIRMATION — the user will see a dialog before this runs. " +
            "Use this when the user asks to 'create', 'write', or 'save' " +
            "a text file. Refuses to overwrite existing files."
    override val argumentsSchema =
        """{"path":"<parent path>","name":"<file name>","content":"<text>"}"""
    override val permissionLevel = ToolPermissionLevel.CONFIRMATION_REQUIRED

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        var parentPath = args.optString("path")
        val name = args.optString("name")
        val content = args.optString("content") ?: ""
        if (name.isNullOrBlank()) return errorResult("Missing 'name' argument.")

        // v0.4.1: same fallback logic as CreateFolderTool.
        val hasAccess = storage.hasFullStorageAccess()
        if (parentPath.isNullOrBlank()) {
            parentPath = storage.privateDocumentsPath()
        } else if (!hasAccess && storage.isSharedStoragePath(parentPath)) {
            val privatePath = storage.privateDocumentsPath()
            val created = storage.createTextFile(privatePath, name, content)
            return if (created != null) {
                ToolResult(
                    success = true,
                    data = """{"path":"$created","name":"$name","bytes_written":${content.length},"created":true,"fallback_to_private":true,"note":"Shared storage not writable without 'All files access' permission. Created in app-private Documents instead."}""",
                    metadata = mapOf("path" to created, "name" to name, "fallback" to "true")
                )
            } else {
                errorResult(
                    "Could not create file '$name' in either '$parentPath' or the fallback " +
                        "private directory. A file with that name may already exist."
                )
            }
        }

        val created = storage.createTextFile(parentPath, name, content)
            ?: return errorResult(
                "Could not create file '$name' in '$parentPath'. " +
                    "The parent may not exist, may not be writable (the app needs " +
                    "'All files access' permission to write to shared storage on " +
                    "Android 10+ — open Settings → AI Configuration → 'Grant all " +
                    "files access'), or a file with that name may already exist."
            )
        val obj = buildJsonObject {
            put("path", created)
            put("name", name)
            put("bytes_written", content.length)
            put("created", true)
        }
        return ToolResult(
            success = true,
            data = obj.toString(),
            metadata = mapOf(
                "path" to created,
                "name" to name,
                "bytes_written" to content.length.toString()
            )
        )
    }
}

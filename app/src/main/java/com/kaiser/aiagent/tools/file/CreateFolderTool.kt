package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CONFIRMATION_REQUIRED — creates a new folder at [parentPath]/[name].
 *
 * The PermissionManager suspends execution and asks the user via a
 * confirmation dialog before this tool's [execute] method runs.
 *
 * Arguments:
 *   {"path": "/storage/emulated/0/Documents", "name": "Physics"}
 *
 * Refuses to create folders whose name contains ".." or path traversal
 * characters. Refuses to overwrite existing files (only folders can
 * already exist — returns the existing folder path in that case).
 */
class CreateFolderTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "create_folder"
    override val description =
        "Creates a new folder at the given parent path. REQUIRES USER " +
            "CONFIRMATION — the user will see a dialog before this runs. " +
            "Use this when the user asks to 'create', 'make', or 'add' a " +
            "folder. Refuses to overwrite existing files."
    override val argumentsSchema = """{"path":"<parent path>","name":"<folder name>"}"""
    override val permissionLevel = ToolPermissionLevel.CONFIRMATION_REQUIRED

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        val parentPath = args.optString("path")
        val name = args.optString("name")
        if (parentPath.isNullOrBlank()) return errorResult("Missing 'path' argument.")
        if (name.isNullOrBlank()) return errorResult("Missing 'name' argument.")
        val created = storage.createFolder(parentPath, name)
            ?: return errorResult(
                "Could not create folder '$name' in '$parentPath'. " +
                    "The parent may not exist, may not be writable, " +
                    "or a file with that name may already exist."
            )
        val obj = buildJsonObject {
            put("path", created)
            put("name", name)
            put("created", true)
        }
        return ToolResult(
            success = true,
            data = obj.toString(),
            metadata = mapOf("path" to created, "name" to name)
        )
    }
}

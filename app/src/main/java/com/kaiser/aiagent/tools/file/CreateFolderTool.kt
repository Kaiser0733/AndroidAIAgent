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
        var parentPath = args.optString("path")
        val name = args.optString("name")
        if (name.isNullOrBlank()) return errorResult("Missing 'name' argument.")

        // v0.4.1: if the user didn't specify a path, OR the path is in
        // shared storage but the app doesn't have MANAGE_EXTERNAL_STORAGE,
        // fall back to the app's private Documents directory. This makes
        // "create a folder called Physics" work even without the special
        // permission.
        val hasAccess = storage.hasFullStorageAccess()
        if (parentPath.isNullOrBlank()) {
            parentPath = storage.privateDocumentsPath()
        } else if (!hasAccess && storage.isSharedStoragePath(parentPath)) {
            // User asked for shared storage but we don't have access —
            // fall back to private storage and note it in the result.
            val privatePath = storage.privateDocumentsPath()
            val created = storage.createFolder(privatePath, name)
            return if (created != null) {
                ToolResult(
                    success = true,
                    data = """{"path":"$created","name":"$name","created":true,"fallback_to_private":true,"note":"Shared storage not writable without 'All files access' permission. Created in app-private Documents instead. Open Settings → AI Configuration → 'Grant all files access' to enable shared storage writes."}""",
                    metadata = mapOf("path" to created, "name" to name, "fallback" to "true")
                )
            } else {
                errorResult(
                    "Could not create folder '$name' in either '$parentPath' or the fallback " +
                        "private directory. The parent may not exist or may not be writable."
                )
            }
        }

        val created = storage.createFolder(parentPath, name)
            ?: return errorResult(
                "Could not create folder '$name' in '$parentPath'. " +
                    "The parent may not exist, may not be writable (the app needs " +
                    "'All files access' permission to write to shared storage on " +
                    "Android 10+ — open Settings → AI Configuration → 'Grant all " +
                    "files access'), or a file with that name may already exist."
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

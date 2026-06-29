package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SAFE — creates a new folder at [parentPath]/[name].
 *
 * v0.5.15: changed from CONFIRMATION_REQUIRED to SAFE. Also adds
 * path resolution for short names like "Documents".
 */
class CreateFolderTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "create_folder"
    override val description =
        "Creates a new folder at the given parent path. " +
            "Use this when the user asks to 'create', 'make', or 'add' a " +
            "folder. Refuses to overwrite existing files."
    override val argumentsSchema = """{"path":"<parent path>","name":"<folder name>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        var parentPath = args.optString("path")
        val name = args.optString("name")
        if (name.isNullOrBlank()) return errorResult("Missing 'name' argument.")

        // v0.5.15: resolve short path names
        parentPath = resolvePath(parentPath)

        // Try to create the folder.
        val created = storage.createFolder(parentPath, name)
        if (created != null) {
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

        // If shared storage failed, try the app's private Documents dir.
        val privatePath = storage.privateDocumentsPath()
        val fallback = storage.createFolder(privatePath, name)
        if (fallback != null) {
            return ToolResult(
                success = true,
                data = """{"path":"$fallback","name":"$name","created":true,"note":"Created in app-private storage at $fallback (shared storage was not writable)."}""",
                metadata = mapOf("path" to fallback, "name" to name, "fallback" to "true")
            )
        }

        return errorResult(
            "Could not create folder '$name'. The folder may not exist or may not be writable. " +
                "Make sure 'All files access' is granted in Settings → Storage Access."
        )
    }

    private fun resolvePath(path: String?): String {
        if (path.isNullOrBlank()) return storage.privateDocumentsPath()
        if (path.startsWith("/")) return path
        val roots = mapOf(
            "documents" to "/storage/emulated/0/Documents",
            "downloads" to "/storage/emulated/0/Download",
            "download" to "/storage/emulated/0/Download",
            "pictures" to "/storage/emulated/0/Pictures",
            "music" to "/storage/emulated/0/Music",
            "movies" to "/storage/emulated/0/Movies",
            "dcim" to "/storage/emulated/0/DCIM",
            "internal" to storage.privateDocumentsPath()
        )
        val resolved = roots[path.lowercase()]
        if (resolved != null) return resolved
        return "/storage/emulated/0/$path"
    }
}

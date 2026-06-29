package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SAFE — creates a new text file at [parentPath]/[name] with the given [content].
 *
 * v0.5.15: changed from CONFIRMATION_REQUIRED to SAFE (confirmation dialogs
 * were breaking the agent pipeline). Also adds path resolution — if the
 * model sends "Documents" instead of "/storage/emulated/0/Documents",
 * the tool resolves it automatically.
 */
class CreateTextFileTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "create_text_file"
    override val description =
        "Creates a new text file with the given content. " +
            "Use this when the user asks to 'create', 'write', or 'save' " +
            "a text file. Refuses to overwrite existing files."
    override val argumentsSchema =
        """{"path":"<parent path>","name":"<file name>","content":"<text>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val args = parseArgs(arguments)
        var parentPath = args.optString("path")
        val name = args.optString("name")
        val content = args.optString("content") ?: ""
        if (name.isNullOrBlank()) return errorResult("Missing 'name' argument.")

        // v0.5.15: resolve short path names to full paths.
        // The model often sends "Documents" or "Download" instead of
        // "/storage/emulated/0/Documents". Resolve it here.
        parentPath = resolvePath(parentPath)

        // Try to create the file.
        val created = storage.createTextFile(parentPath, name, content)
        if (created != null) {
            val obj = buildJsonObject {
                put("path", created)
                put("name", name)
                put("bytes_written", content.length)
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
        val fallback = storage.createTextFile(privatePath, name, content)
        if (fallback != null) {
            return ToolResult(
                success = true,
                data = """{"path":"$fallback","name":"$name","bytes_written":${content.length},"created":true,"note":"Created in app-private storage at $fallback (shared storage was not writable)."}""",
                metadata = mapOf("path" to fallback, "name" to name, "fallback" to "true")
            )
        }

        return errorResult(
            "Could not create file '$name'. The folder may not exist or may not be writable. " +
                "Make sure 'All files access' is granted in Settings → Storage Access."
        )
    }

    /**
     * Resolves short path names to full absolute paths.
     * "Documents" -> "/storage/emulated/0/Documents"
     * "Download" -> "/storage/emulated/0/Download"
     * Already-absolute paths are returned as-is.
     */
    private fun resolvePath(path: String?): String {
        if (path.isNullOrBlank()) return storage.privateDocumentsPath()
        if (path.startsWith("/")) return path

        // Try to resolve against known storage roots
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

        // If it looks like a subfolder, try /storage/emulated/0/<path>
        return "/storage/emulated/0/$path"
    }
}

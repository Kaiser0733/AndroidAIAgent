package com.kaiser.aiagent.tools.file

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SAFE — lists the storage roots accessible on the device
 * (Internal storage, Downloads, Documents, Pictures, Music, Movies).
 *
 * No arguments.
 */
class ListStorageRootsTool(storage: StorageRepository) : BaseFileTool(storage) {
    override val name = "list_storage_roots"
    override val description =
        "Lists the storage roots accessible on the device (Internal storage, " +
            "Downloads, Documents, Pictures, Music, Movies). Use this first " +
            "if you don't know which directory to look in. Takes no arguments."
    override val argumentsSchema = "{}"
    override val permissionLevel = ToolPermissionLevel.SAFE

    override suspend fun execute(arguments: String): ToolResult {
        val roots = storage.listStorageRoots()
        val arr = buildJsonArray {
            for (r in roots) {
                add(buildJsonObject {
                    put("name", r.displayName)
                    put("path", storage.rootPath(r) ?: "")
                })
            }
        }
        val obj = buildJsonObject {
            put("roots", arr)
            put("count", roots.size)
        }
        return ToolResult(
            success = true,
            data = obj.toString(),
            metadata = mapOf("root_count" to roots.size.toString())
        )
    }
}

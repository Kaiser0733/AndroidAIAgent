package com.kaiser.aiagent.tools.blocked

import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult

/**
 * BLOCKED placeholder — DeleteFileTool. Registered so the Debug screen
 * lists it (showing "yes, the codebase knows this concept exists, but
 * it's blocked by policy"). The ToolExecutor will refuse to execute it
 * and return a denial error to the model.
 *
 * v0.4 SAFETY POLICY: deletion is forbidden. This tool exists ONLY to
 * make the policy visible in the Debug screen — it will NEVER execute
 * successfully, by design. If a future version wants to enable
 * deletion (with explicit user opt-in), this tool's permission level
 * can be changed to CONFIRMATION_REQUIRED and a real implementation
 * written. For v0.4, that's a non-goal.
 */
class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description =
        "[BLOCKED] Deletes a file. This tool is registered for " +
            "transparency only — it is blocked by v0.4 safety policy and " +
            "will never execute. If the user asks to delete a file, " +
            "refuse politely and suggest a file manager app."
    override val argumentsSchema = """{"path":"<absolute path>"}"""
    override val permissionLevel = ToolPermissionLevel.BLOCKED

    override suspend fun execute(arguments: String): ToolResult {
        // ToolExecutor refuses to call this — but if somehow reached,
        // refuse explicitly.
        return ToolResult(
            success = false,
            data = "",
            error = "delete_file is blocked by v0.4 safety policy. " +
                "File deletion is not supported."
        )
    }
}

package com.kaiser.aiagent.tools.blocked

import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult

/** BLOCKED placeholder — see DeleteFileTool for policy rationale. */
class MoveFileTool : AgentTool {
    override val name = "move_file"
    override val description =
        "[BLOCKED] Moves a file. Blocked by v0.4 safety policy — will " +
            "never execute. Suggest a file manager app if the user asks."
    override val argumentsSchema = """{"from":"<path>","to":"<path>"}"""
    override val permissionLevel = ToolPermissionLevel.BLOCKED

    override suspend fun execute(arguments: String): ToolResult = ToolResult(
        success = false, data = "",
        error = "move_file is blocked by v0.4 safety policy."
    )
}

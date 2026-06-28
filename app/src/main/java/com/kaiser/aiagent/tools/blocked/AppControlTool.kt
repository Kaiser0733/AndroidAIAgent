package com.kaiser.aiagent.tools.blocked

import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult

/**
 * BLOCKED placeholder for AppControlTool. v0.6+ may implement app
 * launching / force-stopping; v0.4 explicitly refuses to avoid
 * accidental app manipulation.
 */
class AppControlTool : AgentTool {
    override val name = "app_control"
    override val description =
        "[BLOCKED] Opens, closes, or force-stops another app. Blocked by " +
            "v0.4 safety policy. v0.6+ may introduce this with explicit " +
            "user opt-in."
    override val argumentsSchema = """{"action":"<open|close|force_stop>","package":"<pkg>"}"""
    override val permissionLevel = ToolPermissionLevel.BLOCKED

    override suspend fun execute(arguments: String): ToolResult = ToolResult(
        success = false, data = "",
        error = "app_control is blocked by v0.4 safety policy."
    )
}

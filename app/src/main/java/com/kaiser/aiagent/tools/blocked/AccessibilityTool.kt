package com.kaiser.aiagent.tools.blocked

import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult

/**
 * BLOCKED placeholder for AccessibilityTool. v0.5+ may implement a real
 * accessibility-service-backed tool; v0.4 explicitly refuses to avoid
 * the risk of accidental UI automation.
 */
class AccessibilityTool : AgentTool {
    override val name = "accessibility_action"
    override val description =
        "[BLOCKED] Performs an accessibility action (tap, swipe, type) " +
            "in another app. Blocked by v0.4 safety policy. v0.5+ may " +
            "introduce this with explicit user opt-in."
    override val argumentsSchema = """{"action":"<tap|swipe|type>","target":"<...>"}"""
    override val permissionLevel = ToolPermissionLevel.BLOCKED

    override suspend fun execute(arguments: String): ToolResult = ToolResult(
        success = false, data = "",
        error = "accessibility_action is blocked by v0.4 safety policy."
    )
}

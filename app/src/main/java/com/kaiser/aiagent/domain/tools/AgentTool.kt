package com.kaiser.aiagent.domain.tools

/**
 * A single capability the agent can invoke. Implementations live in
 * `com.kaiser.aiagent.tools.*` and are registered with [ToolRegistry]
 * at app startup.
 *
 * v0.4 changes:
 *  - [execute] now returns the new [ToolResult] shape (success/data/error/metadata).
 *  - Added [permissionLevel] — every tool must declare how dangerous it is.
 *    The [PermissionManager] enforces this before [execute] is called.
 *
 * SECURITY: tools execute on the device with the app's permissions.
 * Be conservative — implement only safe, read-only, side-effect-free
 * tools unless you have a strong reason to do otherwise. Tools that
 * modify the file system or any persisted state MUST declare
 * [ToolPermissionLevel.CONFIRMATION_REQUIRED].
 *
 * The [arguments] parameter is a JSON-encoded string. Tools are
 * responsible for parsing it themselves (typically via
 * kotlinx.serialization). This keeps the interface simple and lets
 * each tool define its own argument schema.
 */
interface AgentTool {
    /** Stable identifier used by the model in tool-call requests. */
    val name: String

    /** Human-readable description shown to the model in the system prompt. */
    val description: String

    /**
     * JSON schema for the arguments object. Shown to the model so it
     * knows what fields to populate. Use an empty string for tools
     * that take no arguments.
     */
    val argumentsSchema: String
        get() = "{}"

    /**
     * Permission level required to execute this tool. v0.4 default is
     * [ToolPermissionLevel.SAFE] for backward compatibility with v0.3
     * tools. New tools that modify state MUST override this to
     * [ToolPermissionLevel.CONFIRMATION_REQUIRED].
     */
    val permissionLevel: ToolPermissionLevel
        get() = ToolPermissionLevel.SAFE

    /**
     * Executes the tool and returns a [ToolResult]. The [PermissionManager]
     * calls this only after checking [permissionLevel]. Tools should
     * throw on unexpected failures — the [ToolExecutor] catches and
     * surfaces them as a failed [ToolResult].
     */
    suspend fun execute(arguments: String): ToolResult
}

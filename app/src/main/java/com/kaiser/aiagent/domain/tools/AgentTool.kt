package com.kaiser.aiagent.domain.tools

/**
 * A single capability the agent can invoke. Implementations live in
 * `com.kaiser.aiagent.tools.*` and are registered with [ToolRegistry]
 * at app startup.
 *
 * SECURITY: tools execute on the device with the app's permissions.
 * Be conservative — implement only safe, read-only, side-effect-free
 * tools unless you have a strong reason to do otherwise. v0.3 ships
 * three demo tools (get_time, app_info, device_info) that are all
 * read-only.
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
     * Executes the tool and returns a string result. The result is
     * always a string — structured data should be JSON-encoded by the
     * tool. Throwing is allowed; the [ToolExecutor] will catch and
     * surface the error to the model as a tool-result message.
     */
    suspend fun execute(arguments: String): String
}

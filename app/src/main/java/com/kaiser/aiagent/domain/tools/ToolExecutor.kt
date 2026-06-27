package com.kaiser.aiagent.domain.tools

import timber.log.Timber

/**
 * Executes a [ToolCall] against the [ToolRegistry], going through the
 * [PermissionManager] first. Catches all exceptions so a misbehaving
 * tool cannot crash the agent loop.
 *
 * v0.4 changes:
 *  - Calls [PermissionManager.enforce] before [AgentTool.execute]. If
 *    denied, returns a failed [ToolResult] with the denial reason.
 *  - Returns the new [ToolResult] shape (success/data/error/metadata).
 *  - Records duration and result_count metadata when possible.
 *
 * @param onResult optional callback invoked after every execution —
 *   used by the [com.kaiser.aiagent.domain.agent.AgentRuntime] to
 *   update [com.kaiser.aiagent.domain.agent.AgentState] for the
 *   Debug screen.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val permissionManager: PermissionManager
) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true; isLenient = true
    }

    /**
     * Executes the given tool call. Returns a [ToolResult] — never throws.
     *
     * Flow:
     *   1. Look up the tool by name. Unknown -> failed result.
     *   2. Validate arguments JSON parses. Invalid -> failed result.
     *   3. Ask [PermissionManager] to enforce the tool's permission level.
     *      Denied -> failed result with the denial reason.
     *   4. Call the tool's [execute]. Exception -> failed result.
     *   5. Augment the returned result with `duration_ms` metadata.
     */
    suspend fun execute(
        call: ToolCall,
        conversationId: String? = null,
        onResult: ((String, ToolResult) -> Unit)? = null
    ): ToolResult {
        val started = System.currentTimeMillis()
        val tool = registry[call.tool]
        if (tool == null) {
            val result = ToolResult(
                success = false,
                data = "",
                error = "Unknown tool: ${call.tool}",
                metadata = mapOf("duration_ms" to (System.currentTimeMillis() - started).toString())
            )
            onResult?.invoke(call.tool, result)
            return result
        }
        // Validate arguments string parses as JSON (even if it's just {}).
        val normalizedArgs = try {
            json.parseToJsonElement(call.arguments.ifBlank { "{}" }).toString()
        } catch (e: Exception) {
            val result = ToolResult(
                success = false,
                data = "",
                error = "Invalid arguments JSON: ${e.message}",
                metadata = mapOf("duration_ms" to (System.currentTimeMillis() - started).toString())
            )
            onResult?.invoke(call.tool, result)
            return result
        }

        // Enforce permission level — may suspend for user confirmation.
        when (val enforcement = permissionManager.enforce(tool, normalizedArgs, conversationId)) {
            is PermissionManager.ExecutionResult.Denied -> {
                val result = ToolResult(
                    success = false,
                    data = "",
                    error = enforcement.reason,
                    metadata = mapOf("duration_ms" to (System.currentTimeMillis() - started).toString())
                )
                onResult?.invoke(call.tool, result)
                return result
            }
            is PermissionManager.ExecutionResult.Granted -> { /* proceed */ }
        }

        return try {
            val result = tool.execute(normalizedArgs)
            val withMeta = result.copy(
                metadata = result.metadata + ("duration_ms" to
                    (System.currentTimeMillis() - started).toString())
            )
            Timber.i(
                "Tool %s executed in %d ms: %s",
                call.tool,
                (System.currentTimeMillis() - started),
                withMeta.summary()
            )
            onResult?.invoke(call.tool, withMeta)
            withMeta
        } catch (t: Throwable) {
            val result = ToolResult(
                success = false,
                data = "",
                error = t.message ?: t.javaClass.simpleName,
                metadata = mapOf("duration_ms" to (System.currentTimeMillis() - started).toString())
            )
            Timber.w(t, "Tool %s threw", call.tool)
            onResult?.invoke(call.tool, result)
            result
        }
    }
}

package com.kaiser.aiagent.domain.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Result of a single tool execution. Always serialized as JSON before
 * being handed back to the model so the model can parse it reliably.
 */
@Serializable
data class ToolResult(
    val tool: String,
    val ok: Boolean,
    val output: String,
    @SerialName("error_message") val errorMessage: String? = null,
    /** Wall-clock duration in milliseconds. */
    val durationMs: Long
) {
    /** Renders the result as a string for inclusion in a tool-role message. */
    fun render(): String = Json.encodeToString(serializer(), this)
}

/**
 * Executes a [ToolCall] against the [ToolRegistry] and returns a
 * [ToolResult]. Catches all exceptions so a misbehaving tool cannot
 * crash the agent loop.
 *
 * v0.3 executes tools sequentially. v0.4 can add parallel execution
 * for batched tool calls.
 */
class ToolExecutor(
    private val registry: ToolRegistry
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Executes the given tool call. Returns a [ToolResult] — never throws.
     * The result is also recorded via [onResult] callback if set, so the
     * Debug screen can show "last tool execution".
     */
    suspend fun execute(
        call: ToolCall,
        onResult: ((ToolResult) -> Unit)? = null
    ): ToolResult {
        val started = System.currentTimeMillis()
        val tool = registry[call.tool]
        if (tool == null) {
            val result = ToolResult(
                tool = call.tool,
                ok = false,
                output = "",
                errorMessage = "Unknown tool: ${call.tool}",
                durationMs = System.currentTimeMillis() - started
            )
            onResult?.invoke(result)
            return result
        }
        // Validate the arguments string parses as JSON (even if it's just {}).
        val normalizedArgs = try {
            json.parseToJsonElement(call.arguments.ifBlank { "{}" }).toString()
        } catch (e: Exception) {
            val result = ToolResult(
                tool = call.tool,
                ok = false,
                output = "",
                errorMessage = "Invalid arguments JSON: ${e.message}",
                durationMs = System.currentTimeMillis() - started
            )
            onResult?.invoke(result)
            return result
        }
        return try {
            val output = tool.execute(normalizedArgs)
            val result = ToolResult(
                tool = call.tool,
                ok = true,
                output = output,
                durationMs = System.currentTimeMillis() - started
            )
            Timber.i("Tool %s executed in %d ms: %s", call.tool, result.durationMs, output.take(80))
            onResult?.invoke(result)
            result
        } catch (t: Throwable) {
            val result = ToolResult(
                tool = call.tool,
                ok = false,
                output = "",
                errorMessage = t.message ?: t.javaClass.simpleName,
                durationMs = System.currentTimeMillis() - started
            )
            Timber.w(t, "Tool %s threw", call.tool)
            onResult?.invoke(result)
            result
        }
    }
}

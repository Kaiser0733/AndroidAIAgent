package com.kaiser.aiagent.domain.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Standard JSON tool-call format used by the agent. The model is
 * instructed (via the system prompt) to emit a single one of these
 * objects when it wants to call a tool, instead of (or in addition
 * to) its normal text response.
 *
 * Example:
 * ```json
 * {"tool": "get_time", "arguments": {}}
 * ```
 *
 * The [ToolCallParser] extracts these from the model's response. The
 * [ToolExecutor] then runs the tool and returns the result as a
 * tool-role message for the next turn.
 */
@Serializable
data class ToolCall(
    val tool: String,
    val arguments: String = "{}"
)

/**
 * Result of attempting to parse a tool call from the model's response.
 *  - [None] — no tool call detected, the response is a normal message.
 *  - [Found] — a tool call was detected and parsed.
 *  - [Malformed] — the response looked like a tool call but could not
 *    be parsed (the model produced invalid JSON or an unknown tool).
 */
sealed class ToolCallParseResult {
    data object None : ToolCallParseResult()
    data class Found(val call: ToolCall) : ToolCallParseResult()
    data class Malformed(val raw: String, val error: String) : ToolCallParseResult()
}

/**
 * Extracts [ToolCall]s from the model's response text. The parser is
 * intentionally permissive: it will look for the first JSON object in
 * the response that contains a `tool` field. If the model wraps the
 * call in markdown code fences (```json ... ```) or prose, the parser
 * will still find it.
 *
 * v0.3 supports a single tool call per assistant turn. If the model
 * emits multiple, only the first is honored. (Multi-call batching is
 * a v0.4 concern.)
 */
class ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(response: String): ToolCallParseResult {
        // Fast path: no `{` means no possible tool call.
        if (!response.contains("{")) return ToolCallParseResult.None

        // Find every top-level JSON object in the response and try to
        // parse each as a ToolCall. We walk through the string looking
        // for balanced `{...}` regions.
        var depth = 0
        var start = -1
        for (i in response.indices) {
            val c = response[i]
            when (c) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val candidate = response.substring(start, i + 1)
                            val result = tryParse(candidate)
                            if (result is ToolCallParseResult.Found) return result
                        }
                    }
                }
            }
        }
        // If we got here, no candidate parsed successfully but we did
        // see at least one brace — check if the response *looked* like
        // a tool call (contained "tool" key) to flag malformation.
        return if (response.contains("\"tool\"")) {
            ToolCallParseResult.Malformed(response, "Saw 'tool' key but no valid JSON object parsed")
        } else {
            ToolCallParseResult.None
        }
    }

    private fun tryParse(candidate: String): ToolCallParseResult {
        return try {
            val call = json.decodeFromString(ToolCall.serializer(), candidate)
            if (call.tool.isBlank()) {
                ToolCallParseResult.Malformed(candidate, "Empty tool name")
            } else {
                ToolCallParseResult.Found(call)
            }
        } catch (e: Exception) {
            // Not a tool call object (could be any other JSON). Treat as None
            // so we keep scanning for subsequent objects.
            ToolCallParseResult.None
        }
    }
}

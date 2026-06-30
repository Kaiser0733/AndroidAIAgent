package com.kaiser.aiagent.domain.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Standard JSON tool-call format used by the agent. The model is
 * instructed (via the system prompt) to emit a single one of these
 * objects when it wants to call a tool, instead of (or in addition
 * to) its normal text response.
 *
 * Canonical format:
 * ```json
 * {"tool": "get_time", "arguments": {}}
 * ```
 *
 * The parser also accepts several aliases that different models emit:
 *  - `name` instead of `tool`  (common in OpenAI function-calling style)
 *  - `function` instead of `tool`  (some models wrap the name)
 *  - `parameters` / `params` / `args` instead of `arguments`
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
 * intentionally permissive:
 *  - Looks for the first JSON object containing a tool-name field.
 *  - Handles markdown code fences (```json ... ``` or ``` ... ```).
 *  - Accepts `tool`, `name`, or `function` as the tool-name field.
 *  - Accepts `arguments`, `parameters`, `params`, or `args` as the
 *    arguments field.
 *  - If the model emits the function-calling wrapper shape
 *    `{"function":{"name":"...","arguments":"..."}}` (OpenAI style),
 *    unwraps it correctly.
 *
 * v0.3 supports a single tool call per assistant turn. If the model
 * emits multiple, only the first is honored.
 */
class ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(response: String): ToolCallParseResult {
        if (!response.contains("{")) return ToolCallParseResult.None

        // Strip markdown code fences — some models wrap tool calls as
        // ```json\n{"tool":"..."}\n``` even though the prompt says not to.
        val cleaned = stripCodeFences(response)

        // Find every top-level JSON object in the cleaned response and
        // try to parse each as a tool call.
        var depth = 0
        var start = -1
        var sawToolKey = false
        for (i in cleaned.indices) {
            val c = cleaned[i]
            when (c) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val candidate = cleaned.substring(start, i + 1)
                            if (looksLikeToolKey(candidate)) sawToolKey = true
                            val result = tryParse(candidate)
                            if (result is ToolCallParseResult.Found) return result
                        }
                    }
                }
            }
        }
        return if (sawToolKey || cleaned.contains("\"tool\"") ||
            cleaned.contains("\"name\"") || cleaned.contains("\"function\"")
        ) {
            ToolCallParseResult.Malformed(
                response.take(500),
                "Saw a tool-keyword but no valid tool-call object parsed"
            )
        } else {
            ToolCallParseResult.None
        }
    }

    /** Removes ``` ... ``` and ```json ... ``` fences, leaving just the content. */
    private fun stripCodeFences(text: String): String {
        val regex = Regex("""```(?:json|JSON)?\s*([\s\S]*?)```""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text
        // If there are fenced blocks, replace each with its inner content.
        // Unfenced content outside the blocks is preserved (the parser
        // will scan it too).
        return matches.fold(text) { acc, m ->
            acc.replace(m.value, m.groupValues[1])
        }
    }

    private fun looksLikeToolKey(candidate: String): Boolean =
        candidate.contains("\"tool\"") || candidate.contains("\"name\"") ||
            candidate.contains("\"function\"")

    private fun tryParse(candidate: String): ToolCallParseResult {
        return try {
            val element = json.parseToJsonElement(candidate)
            if (element !is JsonObject) return ToolCallParseResult.None
            val extracted = extractToolCall(element)
            if (extracted == null) {
                ToolCallParseResult.None
            } else if (extracted.tool.isBlank()) {
                ToolCallParseResult.Malformed(candidate, "Empty tool name")
            } else {
                ToolCallParseResult.Found(extracted)
            }
        } catch (e: Exception) {
            ToolCallParseResult.None
        }
    }

    /**
     * Pulls a [ToolCall] out of a parsed JSON object, handling several
     * shapes the model might emit:
     *   1. {"tool":"X","arguments":{...}}          (our canonical)
     *   2. {"name":"X","arguments":{...}}          (OpenAI function-call)
     *   3. {"function":{"name":"X","arguments":"{...}"}}  (OpenAI wrapped)
     *   4. {"tool":"X","parameters":{...}}         (alternate key)
     *   5. {"name":"X","parameters":{...}}         (alternate key + name)
     */
    private fun extractToolCall(obj: JsonObject): ToolCall? {
        // Direct "tool" field.
        val directTool = obj["tool"]?.let { it as? JsonPrimitive }?.contentOrNull
        // Direct "name" field.
        val directName = obj["name"]?.let { it as? JsonPrimitive }?.contentOrNull
        // Wrapped "function" object — OpenAI's chat-completions shape.
        val wrappedFunction = obj["function"]?.let { it as? JsonObject }
        val wrappedName = wrappedFunction?.get("name")?.let { it as? JsonPrimitive }?.contentOrNull
        val wrappedArguments = wrappedFunction?.get("arguments")?.let { it as? JsonPrimitive }?.contentOrNull

        val toolName = directTool ?: directName ?: wrappedName ?: return null

        // Pick the arguments from any of the known keys. OpenAI's wrapped
        // form sends arguments as a JSON-encoded STRING (not an object),
        // so we honor both shapes.
        val argsElement = obj["arguments"] ?: obj["parameters"] ?:
            obj["params"] ?: obj["args"]
        val argumentsString = when {
            wrappedArguments != null -> wrappedArguments  // already a string
            argsElement == null -> "{}"
            argsElement is JsonPrimitive -> argsElement.contentOrNull ?: "{}"
            argsElement is JsonObject -> argsElement.toString()
            else -> "{}"
        }
        return ToolCall(tool = toolName, arguments = argumentsString)
    }
}

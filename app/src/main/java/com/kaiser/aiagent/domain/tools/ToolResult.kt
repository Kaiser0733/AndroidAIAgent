package com.kaiser.aiagent.domain.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Result of a single tool execution.
 *
 * v0.4 redesigned this from the v0.3 shape (`tool/ok/output/errorMessage/durationMs`)
 * to a cleaner, more useful shape: `success/data/error/metadata`. The old
 * fields are gone — every consumer has been migrated. The migration is
 * internal-only; persisted chat history that references old tool results
 * will be displayed as raw text by the UI (the conversation files store
 * the rendered JSON string, not the deserialized object, so this is safe).
 *
 * @property success true if the tool completed without throwing and
 *   produced a meaningful result.
 * @property data the tool's output. For most tools this is a JSON-
 *   encoded string (the model can parse it). For read_text_file it's
 *   the raw file contents.
 * @property error human-readable error message when [success] is false.
 *   Null when [success] is true.
 * @property metadata optional key-value pairs for diagnostics — e.g.
 *   `{"duration_ms": "234", "result_count": "47"}`. Not shown to the
 *   model; surfaced on the Debug screen.
 */
@Serializable
data class ToolResult(
    val success: Boolean,
    val data: String,
    val error: String? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    /** Renders the result as a string for inclusion in a tool-result message. */
    fun render(): String {
        return Json.encodeToString(serializer(), this)
    }

    /** Convenience for the Debug screen / logging. */
    fun summary(): String =
        if (success) "OK: ${data.take(120)}" else "FAIL: ${error ?: "(no detail)"}"
}

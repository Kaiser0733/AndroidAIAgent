package com.kaiser.aiagent.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Request / response models for an OpenAI-compatible chat completions API
 * (works with GLM-4, OpenAI, and any compatible endpoint).
 *
 * The endpoint and model are configurable from Settings; the request body
 * is the same shape regardless of provider.
 */

@Serializable
data class AiMessage(
    val role: String,          // "system" | "user" | "assistant" | "tool"
    val content: String,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class AiRequest(
    val model: String,
    val messages: List<AiMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.7,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null
) {
    /**
     * Serializes this request to JSON, then merges [extraBody] (a raw
     * JSON object string) into the top level. This is how provider-
     * specific options like NVIDIA's `chat_template_kwargs` get added
     * without bloating the strict schema.
     *
     * Returns null if [extraBody] is blank — the caller should fall
     * back to plain `Json.encodeToString(...)` in that case.
     */
    fun toJsonWithExtraBody(extraBody: String): String {
        if (extraBody.isBlank()) {
            return DefaultJson.encodeToString(serializer(), this)
        }
        val base = DefaultJson.encodeToJsonElement(serializer(), this).jsonObject
        val extra = DefaultJson.parseToJsonElement(extraBody).jsonObject
        val merged = JsonObject(base.toMap() + extra.toMap())
        return DefaultJson.encodeToString(JsonObject.serializer(), merged)
    }

    private companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}

@Serializable
data class AiResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<AiChoice> = emptyList(),
    val usage: AiUsage? = null,
    val error: AiError? = null
)

@Serializable
data class AiChoice(
    val index: Int = 0,
    val message: AiMessage? = null,
    val delta: AiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class AiDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class AiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
data class AiError(
    val message: String,
    val type: String? = null,
    val code: String? = null
)

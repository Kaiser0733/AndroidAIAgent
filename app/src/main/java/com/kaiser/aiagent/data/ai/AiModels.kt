package com.kaiser.aiagent.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("max_tokens") val maxTokens: Int? = null
)

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

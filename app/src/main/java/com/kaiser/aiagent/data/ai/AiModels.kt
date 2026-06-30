package com.kaiser.aiagent.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class AiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<AiToolCall>? = null
)

@Serializable
data class AiToolCall(
    val id: String,
    val type: String = "function",
    val function: AiToolCallFunction
)

@Serializable
data class AiToolCallFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class AiToolDefinition(
    val type: String = "function",
    val function: AiToolFunctionDef
)

@Serializable
data class AiToolFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class AiRequest(
    val model: String,
    val messages: List<AiMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.7,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<AiToolDefinition>? = null
) {
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
            encodeDefaults = false
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
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<AiToolCallDelta>? = null
)

@Serializable
data class AiToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: AiToolCallFunctionDelta? = null
)

@Serializable
data class AiToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
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

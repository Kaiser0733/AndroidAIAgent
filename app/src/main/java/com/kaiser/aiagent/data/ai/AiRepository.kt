package com.kaiser.aiagent.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber

class AiRepository(
    private val settings: AiSettings,
    private val service: AiService
) {
    suspend fun config(): AiConfig = settings.configFlow.first()
    val configFlow: Flow<AiConfig> get() = settings.configFlow

    suspend fun updateConfig(transform: (AiConfig) -> AiConfig) {
        settings.update(transform)
    }

    suspend fun chat(messages: List<AiMessage>): String {
        val cfg = config()
        if (cfg.apiKey.isBlank()) throw AiException("No API key configured.")
        val request = AiRequest(model = cfg.model, messages = messages, stream = false,
            temperature = cfg.temperature, topP = cfg.topP, maxTokens = cfg.maxTokens)
        val response = service.chat(cfg, request)
        response.error?.let { throw AiException(it.message) }
        return response.choices.firstOrNull()?.message?.content
            ?: throw AiException("No content in response")
    }

    /**
     * v0.6: Streaming chat with native function calling support.
     * Returns Flow<StreamEvent> — text tokens, tool call chunks, and done events.
     */
    fun streamChatWithTools(
        messages: List<AiMessage>,
        tools: List<AiToolDefinition>?,
        onStatus: ((String) -> Unit)? = null
    ): Flow<StreamEvent> = flow {
        val cfg = config()
        if (cfg.apiKey.isBlank()) throw AiException("No API key configured.")
        val request = AiRequest(
            model = cfg.model, messages = messages, stream = true,
            temperature = cfg.temperature, topP = cfg.topP, maxTokens = cfg.maxTokens,
            tools = tools
        )
        service.streamChat(cfg, request).collect { emit(it) }
    }

    suspend fun testConnection(): Result<String> {
        return try {
            val cfg = config()
            if (cfg.apiKey.isBlank()) return Result.failure(AiException("API key is empty"))
            val reply = chat(listOf(
                AiMessage(role = "system", content = "Reply with the single word: pong"),
                AiMessage(role = "user", content = "ping")
            ))
            Result.success("OK — model replied: ${reply.take(80)}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package com.kaiser.aiagent.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * High-level entry point to the AI service. Wraps [AiService] and adds:
 *  - Config resolution from [AiSettings] (so callers don't need to pass
 *    config on every call).
 *  - A higher-level [streamChat] that takes plain message DTOs and emits
 *    a final assembled content string per turn.
 *  - A lightweight "test connection" helper used by Settings.
 *
 * Callers should depend on this interface, not on [AiService] directly,
 * so the underlying HTTP client can be swapped without touching call
 * sites.
 */
class AiRepository(
    private val settings: AiSettings,
    private val service: AiService
) {

    /** Current config snapshot (one-shot). */
    suspend fun config(): AiConfig = settings.configFlow.first()

    /** Live config stream. */
    val configFlow: Flow<AiConfig> get() = settings.configFlow

    /** Update persisted config. */
    suspend fun updateConfig(transform: (AiConfig) -> AiConfig) {
        settings.update(transform)
    }

    /**
     * Non-streaming chat. Returns the assistant's full message content.
     * Throws [AiException] on failure.
     */
    suspend fun chat(messages: List<AiMessage>): String {
        val cfg = config()
        if (cfg.apiKey.isBlank()) {
            throw AiException("No API key configured. Open Settings → AI to set one.")
        }
        val request = AiRequest(
            model = cfg.model,
            messages = messages,
            stream = false,
            temperature = cfg.temperature,
            topP = cfg.topP,
            maxTokens = cfg.maxTokens
        )
        val response = service.chat(cfg, request)
        response.error?.let { throw AiException(it.message) }
        return response.choices.firstOrNull()?.message?.content
            ?: throw AiException("No content in response")
    }

    /**
     * Streaming chat. Emits incremental content deltas as they arrive
     * from the model. The Flow completes when the model finishes.
     *
     * Each emitted string is a fragment — callers should append it to
     * the running message text.
     */
    fun streamChat(messages: List<AiMessage>): Flow<String> = flow {
        val cfg = config()
        if (cfg.apiKey.isBlank()) {
            throw AiException("No API key configured. Open Settings → AI to set one.")
        }
        val request = AiRequest(
            model = cfg.model,
            messages = messages,
            stream = true,
            temperature = cfg.temperature,
            topP = cfg.topP,
            maxTokens = cfg.maxTokens
        )
        service.streamChat(cfg, request).collect { emit(it) }
    }

    /**
     * Lightweight connectivity + auth test. Sends a one-token "ping"
     * request and returns true if the API responds with 2xx. Returns
     * an error message string on failure.
     */
    suspend fun testConnection(): Result<String> {
        return try {
            val cfg = config()
            if (cfg.apiKey.isBlank()) {
                return Result.failure(AiException("API key is empty"))
            }
            val reply = chat(
                listOf(
                    AiMessage(role = "system", content = "Reply with the single word: pong"),
                    AiMessage(role = "user", content = "ping")
                )
            )
            Timber.i("AI connection test OK: %s", reply.take(50))
            Result.success("OK — model replied: ${reply.take(80)}")
        } catch (e: Exception) {
            Timber.w(e, "AI connection test failed")
            Result.failure(e)
        }
    }
}

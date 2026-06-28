package com.kaiser.aiagent.data.ai

import com.kaiser.aiagent.data.localai.LocalAiEngine
import com.kaiser.aiagent.data.localai.LocalAiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * High-level entry point to the AI service. v0.5 adds support for
 * on-device inference via [LocalAiEngine].
 *
 * When config.backend == LOCAL, [streamChat] routes to LocalAiEngine
 * instead of the cloud AiService. This means:
 *   - No API key needed
 *   - No rate limits
 *   - No network required (after model download)
 *   - Slower inference (depends on device)
 *
 * The repository handles loading the model on first use and caches the
 * engine instance. If the model file changes, the engine is reloaded.
 */
class AiRepository(
    private val settings: AiSettings,
    private val service: AiService,
    private val localEngine: LocalAiEngine
) {

    /** Current config snapshot (one-shot). */
    suspend fun config(): AiConfig = settings.configFlow.first()

    /** Live config stream. */
    val configFlow: Flow<AiConfig> get() = settings.configFlow

    /** Update persisted config. */
    suspend fun updateConfig(transform: (AiConfig) -> AiConfig) {
        settings.update(transform)
    }

    /** Returns true if on-device AI is supported on this device. */
    fun isLocalAiSupported(): Boolean = localEngine.isSupported()

    /** Returns true if a local model is currently loaded. */
    fun isLocalModelLoaded(): Boolean = localEngine.isModelLoaded()

    /**
     * Non-streaming chat (cloud only). Returns the assistant's full message.
     * Throws [AiException] on failure.
     */
    suspend fun chat(messages: List<AiMessage>): String {
        val cfg = config()
        if (cfg.backend == AiBackend.LOCAL) {
            // For local backend, use streaming and collect all tokens.
            val sb = StringBuilder()
            streamChat(messages).collect { sb.append(it) }
            return sb.toString()
        }
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
     * Streaming chat. Emits incremental content deltas as they arrive.
     *
     * v0.5.5: added [onStatus] callback so the UI can show "Loading
     * model..." while the local model loads (takes 10-30 seconds on
     * mid-range devices).
     */
    fun streamChat(
        messages: List<AiMessage>,
        onStatus: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val cfg = config()

        if (cfg.backend == AiBackend.LOCAL) {
            val modelPath = cfg.localModelPath
            if (modelPath.isNullOrBlank()) {
                throw LocalAiException("No local model configured. Download one via Settings → Models.")
            }

            // Load the model if not already loaded.
            if (!localEngine.isModelLoaded() || localEngine.getLoadedModelPath() != modelPath) {
                onStatus?.invoke("Loading on-device model… (this takes 10-30 seconds on first use)")
                // v0.5.8: use a SIMPLE system prompt for local models.
                // The full tool catalog (~1288 tokens) overwhelms small
                // models like Qwen3-0.6B, producing gibberish output.
                val systemPrompt = "You are a helpful AI assistant on an Android phone. Answer questions simply and clearly."
                val loaded = localEngine.loadModel(modelPath, systemPrompt, 0.3)
                if (!loaded) {
                    val detail = localEngine.lastLoadError ?: "unknown error"
                    throw LocalAiException(
                        "Failed to load on-device model: $detail. " +
                            "If the error persists, try the Qwen3 0.6B model (smallest, most compatible)."
                    )
                }
            }

            onStatus?.invoke("Generating response…")
            val lastUserMsg = messages.lastOrNull { it.role == "user" }?.content
                ?: throw LocalAiException("No user message to send.")

            localEngine.sendMessage(lastUserMsg).collect { emit(it) }
        } else {
            // Cloud API path.
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
    }

    /**
     * Lightweight connectivity + auth test (cloud only). Sends a one-token
     * "ping" request and returns true if the API responds with 2xx.
     */
    suspend fun testConnection(): Result<String> {
        val cfg = config()
        if (cfg.backend == AiBackend.LOCAL) {
            // For local backend, test if the model is loaded.
            return if (localEngine.isModelLoaded()) {
                Result.success("OK — on-device model is loaded and ready.")
            } else {
                Result.failure(LocalAiException("No on-device model loaded. Download one via Settings → Models."))
            }
        }
        return try {
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

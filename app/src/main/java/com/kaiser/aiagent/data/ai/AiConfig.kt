package com.kaiser.aiagent.data.ai

/**
 * Configuration for the AI service. All fields are user-editable from
 * Settings and persisted in DataStore.
 *
 * v0.5 adds [backend] — selects between cloud API and on-device inference.
 * When backend = LOCAL, the cloud fields (apiKey, endpoint, model) are
 * ignored and the LocalAiEngine is used instead.
 */

/** Which AI backend to use. */
enum class AiBackend {
    /** Cloud API (Groq, NVIDIA, Gemini, OpenRouter, etc.) — needs API key. */
    CLOUD,
    /** On-device inference via LiteRT-LM — needs a downloaded .task model. */
    LOCAL
}

data class AiConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val temperature: Double = 0.7,
    val topP: Double? = null,
    val maxTokens: Int? = 1024,
    val extraBody: String = "",
    /** v0.5: which backend to use. Defaults to CLOUD. */
    val backend: AiBackend = AiBackend.CLOUD,
    /** v0.5: path to the local .task model file (when backend = LOCAL). */
    val localModelPath: String? = null,
    val connectTimeoutSec: Long = 30,
    val readTimeoutSec: Long = 180,
    val maxRetries: Int = 2,
    val retryBaseDelayMs: Long = 500
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_EXTRA_BODY = """{"chat_template_kwargs":{"thinking":false}}"""

        // ---- Alternative free endpoints the user can paste into Settings ----
        //
        // Groq (very fast, generous free tier):
        //   endpoint: https://api.groq.com/openai/v1/chat/completions
        //   model:    llama-3.3-70b-versatile
        //   get key:  https://console.groq.com/keys
        //
        // Google Gemini (free tier, OpenAI-compatible shim):
        //   endpoint: https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
        //   model:    gemini-2.0-flash-exp
        //   get key:  https://aistudio.google.com/app/apikey
        //
        // OpenRouter (some free models, including Llama 3.3 70B free):
        //   endpoint: https://openrouter.ai/api/v1/chat/completions
        //   model:    meta-llama/llama-3.3-70b-instruct:free
        //   get key:  https://openrouter.ai/keys
        //
        // GLM-4 (paid):
        //   endpoint: https://open.bigmodel.cn/api/paas/v4/chat/completions
        //   model:    glm-4-flash
        //   get key:  https://open.bigmodel.cn/
        //
        // Local (Llama.cpp / Ollama):
        //   endpoint: http://localhost:11434/v1/chat/completions  (Ollama)
        //   model:    llama3.3
        //   api key:  any non-empty string
    }
}

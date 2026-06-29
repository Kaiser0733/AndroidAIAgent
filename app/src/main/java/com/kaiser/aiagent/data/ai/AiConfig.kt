package com.kaiser.aiagent.data.ai

/**
 * Configuration for the AI service. All fields are user-editable from
 * Settings and persisted in DataStore.
 *
 * The default endpoint points to NVIDIA NIM's OpenAI-compatible API.
 * NVIDIA NIM hosts open models (DeepSeek, Llama, etc.) and offers a
 * free tier via https://build.nvidia.com. Any OpenAI-compatible
 * endpoint will work — OpenAI itself, Groq, Google Gemini's
 * OpenAI-compatible shim, GLM, local Llama.cpp / Ollama, etc.
 *
 * SECURITY: [apiKey] is sensitive. It is stored in DataStore Preferences,
 * which is app-private on Android (other apps cannot read it without
 * root). It is never logged, never included in crash dumps, and never
 * committed to the repository.
 */
data class AiConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val temperature: Double = 0.7,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    /**
     * Provider-specific extra body parameters, sent as a top-level JSON
     * object merged into the request body. Used by some providers for
     * model-specific options — e.g. NVIDIA's DeepSeek models accept
     * `{"chat_template_kwargs":{"thinking":false}}` to disable the
     * reasoning trace.
     *
     * Stored as a raw JSON string; the AiService parses it once per
     * request. Empty string = no extra body.
     */
    val extraBody: String = "",
    /** Per-request connect timeout in seconds. */
    val connectTimeoutSec: Long = 30,
    /**
     * Per-request read timeout in seconds (0 = no timeout for streaming).
     *
     * v0.3.2 raised this from 60 to 180 because some NVIDIA NIM models
     * (notably deepseek-v4-pro) have cold-start latency that can exceed
     * 60s on the free tier. Streaming uses readTimeout=0 (no timeout)
     * so this only affects the non-streaming path used by Test Connection.
     */
    val readTimeoutSec: Long = 180,
    /** Max retries on transient failures (network blips, 5xx, 429). */
    val maxRetries: Int = 2,
    /** Base delay between retries, in milliseconds (doubles each retry). */
    val retryBaseDelayMs: Long = 500
) {
    companion object {
        // ---- Default: NVIDIA NIM (free tier at https://build.nvidia.com) ----
        // Get a key at https://build.nvidia.com/deepseek-ai/deepseek-v4-flash
        // (sign in with email/Google, click "Get API Key").
        //
        // v0.3.2 switched the default model from deepseek-v4-pro to
        // deepseek-v4-flash because the Pro variant has cold-start
        // latency that exceeds the previous 60s read timeout. Flash
        // responds in 2-5s reliably. Pro still works if you switch to
        // it manually in Settings — just be patient on the first call.
        const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        const val DEFAULT_MODEL = "gemini-2.0-flash"
        const val DEFAULT_EXTRA_BODY = ""

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

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
    val connectTimeoutSec: Long = 15,
    /** Per-request read timeout in seconds (0 = no timeout for streaming). */
    val readTimeoutSec: Long = 60,
    /** Max retries on transient failures (network blips, 5xx, 429). */
    val maxRetries: Int = 2,
    /** Base delay between retries, in milliseconds (doubles each retry). */
    val retryBaseDelayMs: Long = 500
) {
    companion object {
        // ---- Default: NVIDIA NIM (free tier at https://build.nvidia.com) ----
        // Get a key at https://build.nvidia.com/deepseek-ai/deepseek-v4-pro
        // (sign in with email/Google, click "Get API Key").
        const val DEFAULT_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val DEFAULT_MODEL = "deepseek-ai/deepseek-v4-pro"
        // Default extra body — disables DeepSeek's "thinking" trace so the
        // response comes back as plain text. Users can override in Settings.
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

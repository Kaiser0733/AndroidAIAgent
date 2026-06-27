package com.kaiser.aiagent.data.ai

/**
 * Configuration for the AI service. All fields are user-editable from
 * Settings and persisted in DataStore.
 *
 * The default endpoint points to the GLM-4 OpenAI-compatible API. Any
 * OpenAI-compatible endpoint will work (OpenAI itself, Together,
 * Anyscale, local Llama.cpp server, etc.).
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
    val maxTokens: Int? = null,
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
        const val DEFAULT_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        const val DEFAULT_MODEL = "glm-4-flash"
    }
}

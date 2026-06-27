package com.kaiser.aiagent.data.ai

import kotlinx.coroutines.flow.Flow

/**
 * Wrapper around [AiConfig] that persists to DataStore. Exposed as a Flow
 * so the [AiService] can pick up config changes (e.g. when the user
 * updates their API key in Settings) without restarting the app.
 *
 * Stored fields:
 *  - apiKey        (String, sensitive)
 *  - endpoint      (String)
 *  - model         (String)
 *  - temperature   (Double)
 *  - maxTokens     (Int, nullable — stored as -1 to indicate "unset")
 */
interface AiSettings {
    val configFlow: Flow<AiConfig>
    suspend fun update(transform: (AiConfig) -> AiConfig)
}

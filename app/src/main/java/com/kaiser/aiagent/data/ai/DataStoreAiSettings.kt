package com.kaiser.aiagent.data.ai

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiSettingsStore by preferencesDataStore("ai_settings")

/**
 * DataStore-backed implementation of [AiSettings].
 *
 * v0.5 adds `backend` and `localModelPath` keys for on-device AI support.
 */
class DataStoreAiSettings(private val context: Context) : AiSettings {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val TOP_P = doublePreferencesKey("top_p")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val EXTRA_BODY = stringPreferencesKey("extra_body")
        // v0.5
        val BACKEND = stringPreferencesKey("backend")           // "CLOUD" or "LOCAL"
        val LOCAL_MODEL_PATH = stringPreferencesKey("local_model_path")
    }

    private fun snapshot(prefs: Preferences): AiConfig = AiConfig(
        apiKey = prefs[Keys.API_KEY] ?: "",
        endpoint = prefs[Keys.ENDPOINT] ?: AiConfig.DEFAULT_ENDPOINT,
        model = prefs[Keys.MODEL] ?: AiConfig.DEFAULT_MODEL,
        temperature = prefs[Keys.TEMPERATURE] ?: 0.7,
        topP = prefs[Keys.TOP_P]?.takeIf { it > 0 },
        maxTokens = prefs[Keys.MAX_TOKENS]?.takeIf { it > 0 },
        extraBody = prefs[Keys.EXTRA_BODY] ?: AiConfig.DEFAULT_EXTRA_BODY,
        backend = prefs[Keys.BACKEND]?.let { runCatching { AiBackend.valueOf(it) }.getOrNull() } ?: AiBackend.CLOUD,
        localModelPath = prefs[Keys.LOCAL_MODEL_PATH]
    )

    override val configFlow: Flow<AiConfig> = context.aiSettingsStore.data.map { snapshot(it) }

    override suspend fun update(transform: (AiConfig) -> AiConfig) {
        context.aiSettingsStore.edit { prefs ->
            val next = transform(snapshot(prefs))
            prefs[Keys.API_KEY] = next.apiKey
            prefs[Keys.ENDPOINT] = next.endpoint
            prefs[Keys.MODEL] = next.model
            prefs[Keys.TEMPERATURE] = next.temperature
            prefs[Keys.TOP_P] = next.topP ?: -1.0
            prefs[Keys.MAX_TOKENS] = next.maxTokens ?: -1
            prefs[Keys.EXTRA_BODY] = next.extraBody
            prefs[Keys.BACKEND] = next.backend.name
            prefs[Keys.LOCAL_MODEL_PATH] = next.localModelPath ?: ""
        }
    }
}

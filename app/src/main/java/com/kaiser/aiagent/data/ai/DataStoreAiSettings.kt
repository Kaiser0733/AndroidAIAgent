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
 * DataStore-backed implementation of [AiSettings]. The API key is stored
 * in plain Preferences DataStore (which is app-private on Android). For
 * a future hardening pass we can migrate to EncryptedSharedPreferences
 * or the Android Keystore — the [AiSettings] interface makes that swap
 * transparent to callers.
 */
class DataStoreAiSettings(private val context: Context) : AiSettings {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")  // -1 = unset
    }

    override val configFlow: Flow<AiConfig> = context.aiSettingsStore.data.map { prefs ->
        AiConfig(
            apiKey = prefs[Keys.API_KEY] ?: "",
            endpoint = prefs[Keys.ENDPOINT] ?: AiConfig.DEFAULT_ENDPOINT,
            model = prefs[Keys.MODEL] ?: AiConfig.DEFAULT_MODEL,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7,
            maxTokens = prefs[Keys.MAX_TOKENS]?.takeIf { it > 0 }
        )
    }

    override suspend fun update(transform: (AiConfig) -> AiConfig) {
        context.aiSettingsStore.edit { prefs ->
            val current = AiConfig(
                apiKey = prefs[Keys.API_KEY] ?: "",
                endpoint = prefs[Keys.ENDPOINT] ?: AiConfig.DEFAULT_ENDPOINT,
                model = prefs[Keys.MODEL] ?: AiConfig.DEFAULT_MODEL,
                temperature = prefs[Keys.TEMPERATURE] ?: 0.7,
                maxTokens = prefs[Keys.MAX_TOKENS]?.takeIf { it > 0 }
            )
            val next = transform(current)
            prefs[Keys.API_KEY] = next.apiKey
            prefs[Keys.ENDPOINT] = next.endpoint
            prefs[Keys.MODEL] = next.model
            prefs[Keys.TEMPERATURE] = next.temperature
            prefs[Keys.MAX_TOKENS] = next.maxTokens ?: -1
        }
    }
}

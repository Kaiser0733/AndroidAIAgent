package com.kaiser.aiagent.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

private val Context.remoteConfigStore by preferencesDataStore("remote_config_prefs")

/**
 * Loads, caches, and exposes the [RemoteConfig].
 *
 * The URL is stored in DataStore so the user can change it from Settings
 * without recompiling. The actual fetch is performed by [fetch] and the
 * last successful result is cached in memory + persisted to DataStore so
 * the app can boot offline using the previously known config.
 */
class RemoteConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        @OptIn(ExperimentalSerializationApi::class)
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val urlKey = stringPreferencesKey("remote_config_url")
    private val cachedConfigKey = stringPreferencesKey("cached_config_json")

    /** Flow of the currently configured remote-config URL. */
    val configUrlFlow: Flow<String> = context.remoteConfigStore.data.map { prefs ->
        prefs[urlKey] ?: DEFAULT_URL
    }

    /** Flow of the last successfully fetched (or default) remote config. */
    val cachedConfigFlow: Flow<RemoteConfig> = context.remoteConfigStore.data.map { prefs ->
        val raw = prefs[cachedConfigKey]
        if (raw.isNullOrBlank()) RemoteConfig()
        else try { json.decodeFromString<RemoteConfig>(raw) } catch (e: Exception) {
            Timber.w(e, "Failed to decode cached remote config, using defaults")
            RemoteConfig()
        }
    }

    /** Update the remote-config URL (called from Settings). */
    suspend fun setConfigUrl(url: String) {
        context.remoteConfigStore.edit { it[urlKey] = url }
    }

    /**
     * Fetches the remote config from the currently configured URL.
     * Returns the freshly parsed [RemoteConfig] (also cached).
     * Throws on network or parsing failure — callers should catch.
     */
    suspend fun fetch(): RemoteConfig {
        val url = configUrlFlow.first()
        Timber.i("Fetching remote config from %s", url)
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RemoteConfigException("HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw RemoteConfigException("Empty response body")
            val parsed = json.decodeFromString<RemoteConfig>(body)
            // Cache the successful result.
            context.remoteConfigStore.edit { it[cachedConfigKey] = body }
            return parsed
        }
    }

    companion object {
        // Live remote config served from the persolist-updates repo (raw URL —
        // always reachable, no GitHub Pages propagation delay). Editable from
        // Settings; this is just the v0.1 default.
        const val DEFAULT_URL =
            "https://raw.githubusercontent.com/Kaiser0733/persolist-updates/main/aiagent-config.json"
    }
}

class RemoteConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

package com.kaiser.aiagent.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun chat(config: AiConfig, request: AiRequest): AiResponse =
        withContext(Dispatchers.IO) {
            val req = buildRequest(config, request.copy(stream = false))
            val client = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(config.readTimeoutSec, TimeUnit.SECONDS)
                .build()
            val response = client.newCall(req).execute()
            response.use {
                if (!response.isSuccessful) {
                    val code = response.code
                    val body = response.body?.string().orEmpty()
                    val errorMsg = try {
                        val parsed = json.parseToJsonElement(body)
                        if (parsed is kotlinx.serialization.json.JsonArray) {
                            val obj = parsed.firstOrNull() as? kotlinx.serialization.json.JsonObject
                            val error = obj?.get("error") as? kotlinx.serialization.json.JsonObject
                            (error?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        } else if (parsed is kotlinx.serialization.json.JsonObject) {
                            val error = parsed["error"] as? kotlinx.serialization.json.JsonObject
                            (error?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        } else null
                    } catch (e: Exception) { null } ?: body.take(200)
                    throw AiException("HTTP $code: $errorMsg")
                }
                val body = response.body?.string() ?: throw AiException("Empty response body")
                try { json.decodeFromString(AiResponse.serializer(), body) }
                catch (e: Exception) { throw AiException("Failed to parse response: ${body.take(200)}") }
            }
        }

    fun streamChat(config: AiConfig, request: AiRequest): Flow<String> = flow {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val req = buildRequest(config, request.copy(stream = true))
        val tokenChannel = Channel<String>(Channel.BUFFERED)
        var streamError: Throwable? = null
        val factory = EventSources.createFactory(client)
        val source = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { eventSource.cancel(); tokenChannel.close(); return }
                try {
                    val parsed = json.decodeFromString(AiResponse.serializer(), data)
                    val delta = parsed.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) tokenChannel.trySend(delta)
                } catch (e: Exception) { Timber.w(e, "Failed to parse SSE chunk: %s", data) }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                streamError = AiException(friendlyHttpMessage(code, t, response?.header("Retry-After")?.toLongOrNull()), t)
                response?.close()
                tokenChannel.close()
            }
            override fun onClosed(eventSource: EventSource) { tokenChannel.close() }
        })
        for (token in tokenChannel) { emit(token) }
        streamError?.let { throw it }
        try { source.cancel() } catch (e: Exception) { }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(config: AiConfig, request: AiRequest): Request {
        val bodyJson = request.toJsonWithExtraBody(config.extraBody)
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val endpoint = config.endpoint.trim().removeSuffix("/")
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    private fun friendlyHttpMessage(code: Int?, t: Throwable?, retryAfterSec: Long?): String = when (code) {
        429 -> { val w = retryAfterSec?.let{"Wait $it seconds"}?:"Wait 30-60 seconds"; "Rate limited (HTTP 429). $w and try again." }
        401 -> "Auth failed (HTTP 401). Check your API key."
        403 -> "Forbidden (HTTP 403). Check the Model field."
        404 -> "Not found (HTTP 404). Endpoint should end with /chat/completions."
        500,502,503,504 -> "Provider server error (HTTP $code). Try again shortly."
        null -> "Network error: ${t?.message ?: "unknown"}"
        else -> "HTTP $code — ${t?.message ?: "no detail"}"
    }
}

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

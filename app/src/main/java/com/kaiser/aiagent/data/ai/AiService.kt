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
import java.util.concurrent.TimeUnit

/** v0.6: streaming events — text tokens OR tool calls OR done */
sealed class StreamEvent {
    data class Text(val token: String) : StreamEvent()
    data class ToolCallChunk(val index: Int, val id: String?, val name: String?, val arguments: String) : StreamEvent()
    data class Done(val finishReason: String?) : StreamEvent()
    data class Error(val error: Throwable) : StreamEvent()
}

class AiService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
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
                    // v0.6.1: show actual error body, not just "no detail"
                    val errorMsg = parseErrorMessage(body) ?: if (body.isNotBlank()) body.take(300) else "HTTP $code (no response body)"
                    throw AiException("HTTP $code: $errorMsg")
                }
                val body = response.body?.string() ?: throw AiException("Empty response body")
                try { json.decodeFromString(AiResponse.serializer(), body) }
                catch (e: Exception) { throw AiException("Failed to parse response: ${body.take(200)}") }
            }
        }

    /**
     * v0.6: streaming with native function calling support.
     * Returns Flow<StreamEvent> — caller handles text, tool calls, and completion.
     *
     * v0.6.7: readTimeout is now 90 seconds (was 0 = infinite). If Groq
     * or any provider opens the connection but stops sending data for
     * 90s, OkHttp throws SocketTimeoutException which surfaces as an
     * error event instead of hanging forever in "thinking" state.
     */
    fun streamChat(config: AiConfig, request: AiRequest): Flow<StreamEvent> = flow {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        val req = buildRequest(config, request.copy(stream = true))
        val channel = Channel<StreamEvent>(Channel.BUFFERED)
        var streamError: Throwable? = null

        val factory = EventSources.createFactory(client)
        val source = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    eventSource.cancel()
                    channel.trySend(StreamEvent.Done(null))
                    channel.close()
                    return
                }
                try {
                    val parsed = json.decodeFromString(AiResponse.serializer(), data)
                    val choice = parsed.choices.firstOrNull() ?: return
                    val delta = choice.delta ?: return

                    // Text content
                    if (!delta.content.isNullOrEmpty()) {
                        channel.trySend(StreamEvent.Text(delta.content))
                    }

                    // Tool call deltas (v0.6)
                    if (!delta.toolCalls.isNullOrEmpty()) {
                        for (tc in delta.toolCalls) {
                            channel.trySend(StreamEvent.ToolCallChunk(
                                index = tc.index,
                                id = tc.id,
                                name = tc.function?.name,
                                arguments = tc.function?.arguments ?: ""
                            ))
                        }
                    }

                    // Finish reason
                    if (choice.finishReason != null) {
                        channel.trySend(StreamEvent.Done(choice.finishReason))
                        eventSource.cancel()
                        channel.close()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse SSE chunk: %s", data)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                val body = try { response?.body?.string() } catch(e:Exception) { null }
                response?.close()
                val detail = parseErrorMessage(body.orEmpty()) ?: body?.take(300) ?: t?.message ?: "unknown"
                streamError = AiException("HTTP $code: $detail", t)
                channel.close()
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }
        })

        for (event in channel) {
            if (event is StreamEvent.Error) {
                streamError = event.error
                break
            }
            emit(event)
        }

        try { source.cancel() } catch (e: Exception) { }
        streamError?.let { throw it }
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

    private fun parseErrorMessage(body: String): String? = try {
        val parsed = json.parseToJsonElement(body)
        if (parsed is kotlinx.serialization.json.JsonArray) {
            val obj = parsed.firstOrNull() as? kotlinx.serialization.json.JsonObject
            val error = obj?.get("error") as? kotlinx.serialization.json.JsonObject
            (error?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
        } else if (parsed is kotlinx.serialization.json.JsonObject) {
            val error = parsed["error"] as? kotlinx.serialization.json.JsonObject
            (error?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
        } else null
    } catch (e: Exception) { null }

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

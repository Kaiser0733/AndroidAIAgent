package com.kaiser.aiagent.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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

/**
 * Low-level HTTP client for an OpenAI-compatible chat completions API.
 *
 * v0.4.5: completely rewrote the streaming path to fix the "stuck in
 * thinking" bug introduced in v0.4.4. The v0.4.4 pre-flight approach
 * doubled API calls (one for the pre-flight, one for the actual stream)
 * which made rate limiting 2x worse AND caused hangs when Groq saw the
 * pre-flight's abrupt connection close.
 *
 * The v0.4.5 approach: open the SSE stream directly with EventSources,
 * handle 429 in the onFailure callback, and retry the entire stream
 * open up to maxRetries times. This uses exactly 1 API call per
 * streaming attempt (same as v0.4.3 and earlier).
 */
class AiService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Non-streaming chat completion. Retries transient failures
     * (IOException, 5xx, 429) up to [AiConfig.maxRetries] times with
     * exponential backoff. Honors the Retry-After header on 429.
     */
    suspend fun chat(config: AiConfig, request: AiRequest): AiResponse =
        withContext(Dispatchers.IO) {
            val req = buildRequest(config, request.copy(stream = false))
            executeWithRetry(config, req) { resp ->
                val body = resp.body?.string()
                    ?: throw AiException("Empty response body")
                json.decodeFromString(AiResponse.serializer(), body)
            }
        }

    /**
     * Streaming chat completion. Returns a cold Flow that, when collected,
     * opens an SSE connection and emits each `delta.content` string as it
     * arrives. The Flow completes when the server closes the stream.
     *
     * v0.4.5: retries on 429 / 5xx / IOException by re-opening the entire
     * SSE stream up to [AiConfig.maxRetries] times. Uses exactly 1 API
     * call per attempt. Honors the Retry-After header on 429.
     */
    fun streamChat(config: AiConfig, request: AiRequest): Flow<String> = flow {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val req = buildRequest(config, request.copy(stream = true))

        var lastError: Throwable? = null
        var completed = false

        for (attempt in 0..config.maxRetries) {
            if (completed) break
            try {
                emitStreamEvents(client, req, config, attempt, this)
                completed = true
            } catch (e: RetryableStreamException) {
                lastError = e.cause
                val delayMs = e.retryAfterMs ?: config.retryBaseDelayMs * (1L shl attempt)
                Timber.i("Stream attempt %d failed (retryable), retrying in %d ms: %s",
                    attempt, delayMs, e.message)
                kotlinx.coroutines.delay(delayMs)
            }
        }
        if (!completed) {
            throw AiException(
                "Streaming failed after ${config.maxRetries + 1} attempts",
                lastError
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Opens a single SSE stream, emits deltas to the flow collector, and
     * throws [RetryableStreamException] for retryable failures (429, 5xx,
     * IOException before any events arrive). Throws [AiException] for
     * non-retryable failures.
     */
    private suspend fun emitStreamEvents(
        client: OkHttpClient,
        req: Request,
        config: AiConfig,
        attempt: Int,
        collector: kotlinx.coroutines.flow.FlowCollector<String>
    ) {
        val eventChannel = kotlinx.coroutines.channels.Channel<String>(
            kotlinx.coroutines.channels.Channel.BUFFERED
        )
        val done = CompletableDeferred<StreamOutcome>()

        val factory = EventSources.createFactory(client)
        var eventsReceived = false

        val source = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                eventsReceived = true
                if (data == "[DONE]") {
                    eventSource.cancel()
                    done.complete(StreamOutcome.Success)
                    eventChannel.close()
                    return
                }
                try {
                    val parsed = json.decodeFromString(AiResponse.serializer(), data)
                    val delta = parsed.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) {
                        eventChannel.trySend(delta)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse SSE chunk: %s", data)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                val retryAfter = response?.header("Retry-After")?.toLongOrNull()
                response?.close()
                val isRetryable = !eventsReceived && (
                    code == 429 || code in 500..599 || t is IOException
                )
                if (isRetryable) {
                    done.complete(StreamOutcome.Retryable(
                        "HTTP $code / ${t?.javaClass?.simpleName}",
                        retryAfter?.times(1000)
                    ))
                } else {
                    val msg = friendlyHttpMessage(code, t, retryAfter)
                    done.complete(StreamOutcome.Fatal(AiException(msg, t)))
                }
                eventChannel.close()
            }

            override fun onClosed(eventSource: EventSource) {
                if (!done.isCompleted) {
                    done.complete(StreamOutcome.Success)
                }
                eventChannel.close()
            }
        })

        try {
            // Emit events as they arrive. If the stream fails before any
            // events arrive, throw RetryableStreamException so the outer
            // loop can retry. If it fails after events arrived, the
            // partial output has already been emitted — throw a fatal
            // AiException so the caller knows the stream was incomplete.
            while (true) {
                select<Unit> {
                    eventChannel.onReceive { collector.emit(it) }
                    done.onAwait { outcome ->
                        when (outcome) {
                            is StreamOutcome.Success -> { /* done */ }
                            is StreamOutcome.Retryable ->
                                throw RetryableStreamException(outcome.message, outcome.retryAfterMs)
                            is StreamOutcome.Fatal -> throw outcome.error
                        }
                    }
                }
                if (done.isCompleted) break
            }
        } finally {
            source.cancel()
            eventChannel.close()
        }
    }

    private sealed class StreamOutcome {
        data object Success : StreamOutcome()
        data class Retryable(val message: String, val retryAfterMs: Long?) : StreamOutcome()
        data class Fatal(val error: AiException) : StreamOutcome()
    }

    private class RetryableStreamException(
        message: String,
        val retryAfterMs: Long?
    ) : Exception(message)

    // ---- Non-streaming retry helper (unchanged from v0.4.4) -----------

    private suspend fun <T> executeWithRetry(
        config: AiConfig,
        request: Request,
        parser: (Response) -> T
    ): T {
        var lastError: Throwable? = null
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSec, TimeUnit.SECONDS)
            .build()

        for (attempt in 0..config.maxRetries) {
            var retryAfter: Long? = null
            try {
                val call = client.newCall(request)
                val response = call.execute()
                response.use {
                    if (response.isSuccessful) {
                        return parser(it)
                    }
                    val code = response.code
                    retryAfter = response.header("Retry-After")?.toLongOrNull()
                    val body = response.body?.string().orEmpty()
                    val isRetryable = code == 429 || code in 500..599
                    if (!isRetryable || attempt == config.maxRetries) {
                        throw AiException(friendlyHttpMessage(code, null, retryAfter))
                    }
                    lastError = AiException("HTTP $code (retryable)")
                }
                val delayMs = retryAfter?.times(1000) ?: config.retryBaseDelayMs * (1L shl attempt)
                Timber.i("Retrying in %d ms (attempt %d/%d)", delayMs, attempt + 1, config.maxRetries)
                kotlinx.coroutines.delay(delayMs)
            } catch (e: IOException) {
                if (attempt == config.maxRetries) {
                    throw AiException(
                        "Network error after ${attempt + 1} attempts: " +
                            "${e.javaClass.simpleName} — ${e.message?.take(120)}",
                        e
                    )
                }
                lastError = e
                val delayMs = config.retryBaseDelayMs * (1L shl attempt)
                Timber.i("Retrying in %d ms (attempt %d/%d)", delayMs, attempt + 1, config.maxRetries)
                kotlinx.coroutines.delay(delayMs)
            }
        }
        throw AiException("Exhausted retries", lastError)
    }

    // ---- Helpers --------------------------------------------------------

    private fun buildRequest(config: AiConfig, request: AiRequest): Request {
        val bodyJson = request.toJsonWithExtraBody(config.extraBody)
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    private fun friendlyHttpMessage(code: Int?, t: Throwable?, retryAfterSec: Long?): String {
        return when (code) {
            429 -> {
                val wait = retryAfterSec?.let { "Wait $it seconds" }
                    ?: "Wait 30-60 seconds"
                "Rate limited (HTTP 429). $wait and try again. " +
                    "Groq free tier: 30 req/min. Consider NVIDIA NIM or " +
                    "Google Gemini in Settings for different limits."
            }
            401 -> "Auth failed (HTTP 401). Check your API key in Settings."
            403 -> "Forbidden (HTTP 403). Check the Model field in Settings."
            404 -> "Not found (HTTP 404). Endpoint URL should end with /chat/completions."
            500, 502, 503, 504 -> "Provider server error (HTTP $code). Try again shortly."
            null -> "Network error: ${t?.message ?: "unknown"}"
            else -> "HTTP $code — ${t?.message ?: "no detail"}"
        }
    }
}

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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
 * Two entry points:
 *  - [chat] — non-streaming. Returns the full [AiResponse].
 *  - [streamChat] — streaming (SSE). Emits each delta as a String; the
 *    Flow completes when the stream ends.
 *
 * Both methods respect the timeouts and retry policy in [AiConfig] and
 * throw [AiException] on failure. The client is stateless — pass the
 * current [AiConfig] to each call.
 *
 * SECURITY: The API key is sent as `Authorization: Bearer <key>`. It is
 * never logged. OkHttp is configured with no logging interceptor to
 * avoid leaking it to logcat.
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
     * exponential backoff.
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
     * v0.4.4: now retries on 429 / 5xx / IOException during the
     * connection phase (before any SSE events arrive). Once streaming
     * starts, failures are terminal (no retry — would lose partial output).
     * The retry uses the `Retry-After` header if the provider sent one,
     * otherwise exponential backoff.
     */
    fun streamChat(config: AiConfig, request: AiRequest): Flow<String> = flow {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val req = buildRequest(config, request.copy(stream = true))

        // v0.4.4: retry the SSE connection up to config.maxRetries times
        // during the connection phase. Once streaming starts, failures are
        // terminal (no retry — would lose partial output). The retry uses
        // the `Retry-After` header if the provider sent one, otherwise
        // exponential backoff.
        var lastError: Throwable? = null
        var connected = false
        for (attempt in 0..config.maxRetries) {
            try {
                val response = client.newCall(req).execute()
                if (response.isSuccessful) {
                    response.close()
                    connected = true
                    break
                }
                val code = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                response.close()
                val isRetryable = code == 429 || code in 500..599
                if (!isRetryable || attempt == config.maxRetries) {
                    throw AiException(friendlyHttpMessage(code, null, retryAfter))
                }
                lastError = AiException("HTTP $code (retryable)")
                val delayMs = retryAfter?.times(1000) ?: config.retryBaseDelayMs * (1L shl attempt)
                Timber.i("Stream pre-flight got %d, retrying in %d ms", code, delayMs)
                kotlinx.coroutines.delay(delayMs)
            } catch (e: java.io.IOException) {
                if (attempt == config.maxRetries) {
                    throw AiException(
                        "Network error after ${attempt + 1} attempts: " +
                            "${e.javaClass.simpleName} — ${e.message?.take(120)}",
                        e
                    )
                }
                lastError = e
                val delayMs = config.retryBaseDelayMs * (1L shl attempt)
                Timber.i("Stream pre-flight IO error, retrying in %d ms", delayMs)
                kotlinx.coroutines.delay(delayMs)
            }
        }
        if (!connected) {
            throw AiException("Could not connect after ${config.maxRetries + 1} attempts", lastError)
        }

        // Open the SSE stream. Use a Channel to bridge the callback-based
        // EventSource to the flow-based emission. The flow collects from
        // the channel and propagates any failure.
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
        val pending = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable>()

        val factory = EventSources.createFactory(client)
        val source = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    eventSource.cancel()
                    pending.complete(Unit)
                    channel.close()
                    return
                }
                try {
                    val parsed = json.decodeFromString(AiResponse.serializer(), data)
                    val delta = parsed.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) {
                        channel.trySend(delta)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse SSE chunk: %s", data)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                val msg = friendlyHttpMessage(code, t, response?.header("Retry-After")?.toLongOrNull())
                Timber.w(t, msg)
                failure.complete(AiException(msg, t))
                channel.close()
            }

            override fun onClosed(eventSource: EventSource) {
                pending.complete(Unit)
                channel.close()
            }
        })

        try {
            // Emit deltas as they arrive. If the stream fails, throw the
            // failure exception (which will propagate to the collector).
            while (true) {
                kotlinx.coroutines.selects.select<Unit> {
                    channel.onReceive { emit(it) }
                    pending.onAwait { /* done */ }
                    failure.onAwait { throw it }
                }
                if (pending.isCompleted || failure.isCompleted) break
            }
        } finally {
            source.cancel()
            channel.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Builds a user-friendly error message for an HTTP failure, honoring
     * the `Retry-After` header if present.
     */
    private fun friendlyHttpMessage(code: Int?, t: Throwable?, retryAfterSec: Long?): String {
        return when (code) {
            429 -> {
                val wait = retryAfterSec?.let { "Wait $it seconds" }
                    ?: "Wait 30-60 seconds"
                "Rate limited by the AI provider (HTTP 429). $wait and try again. " +
                    "Groq's free tier is 30 requests/minute. If you keep hitting the " +
                    "limit, consider switching providers in Settings (NVIDIA NIM, " +
                    "Google Gemini, OpenRouter all have free tiers with different limits)."
            }
            401 -> "Authentication failed (HTTP 401). Your API key is missing, invalid, or expired. " +
                "Open Settings → AI Configuration and re-enter your API key."
            403 -> "Forbidden (HTTP 403). Your API key may not have access to the requested model. " +
                "Check the Model field in Settings → AI Configuration."
            404 -> "Not found (HTTP 404). The API endpoint URL is wrong. Check the Endpoint field " +
                "in Settings → AI Configuration — it should end with /chat/completions."
            500, 502, 503, 504 -> "The AI provider's server errored (HTTP $code). " +
                "Try again in a few seconds."
            null -> "Network error: ${t?.message ?: "unknown"}"
            else -> "HTTP $code — ${t?.message ?: "no detail"}"
        }
    }

    // ---- Helpers --------------------------------------------------------

    private fun buildRequest(config: AiConfig, request: AiRequest): Request {
        val bodyJson = request.toJsonWithExtraBody(config.extraBody)
        val body: RequestBody = bodyJson.toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    /**
     * Executes a synchronous OkHttp call with retry. Retries on:
     *  - IOException (network blip)
     *  - HTTP 429 (rate limited)
     *  - HTTP 5xx (server error)
     * Does NOT retry on 4xx other than 429 (those are caller errors).
     */
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
            try {
                val call: Call = client.newCall(request)
                val response = call.execute()
                response.use {
                    if (response.isSuccessful) {
                        return parser(it)
                    }
                    val code = response.code
                    val body = response.body?.string().orEmpty()
                    val isRetryable = code == 429 || code in 500..599
                    if (!isRetryable || attempt == config.maxRetries) {
                        throw AiException("HTTP $code: $body")
                    }
                    // Fall through to retry
                    lastError = AiException("HTTP $code (retryable)")
                }
            } catch (e: IOException) {
                if (attempt == config.maxRetries) {
                    // Include the exception class name so the user can tell
                    // a timeout (SocketTimeoutException) from a DNS failure
                    // (UnknownHostException) from a TLS failure
                    // (SSLException) — they all surface as "network error"
                    // without this hint.
                    val exType = e.javaClass.simpleName
                    val exMsg = e.message?.take(120) ?: "(no detail)"
                    throw AiException(
                        "Network error after ${attempt + 1} attempts: $exType — $exMsg",
                        e
                    )
                }
                lastError = e
            }
            // Exponential backoff
            val delayMs = config.retryBaseDelayMs * (1L shl attempt)
            Timber.i("Retrying in %d ms (attempt %d/%d)", delayMs, attempt + 1, config.maxRetries)
            kotlinx.coroutines.delay(delayMs)
        }
        throw AiException("Exhausted retries", lastError)
    }
}

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

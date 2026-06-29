package com.kaiser.aiagent.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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
     * Retries are NOT applied to streaming — once the stream has started,
     * a failure is terminal. (Pre-stream connection failures will still
     * throw immediately.)
     */
    fun streamChat(config: AiConfig, request: AiRequest): Flow<String> = callbackFlow {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // no read timeout for streams
            .build()

        val req = buildRequest(config, request.copy(stream = true))
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
                    channel.close()
                    return
                }
                try {
                    val parsed = json.decodeFromString(AiResponse.serializer(), data)
                    val delta = parsed.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) {
                        trySend(delta)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse SSE chunk: %s", data)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // v0.4.1: produce a user-friendly error message for
                // common failure modes (429 rate limit, 401 auth, etc.)
                // instead of the opaque "SSE failure: 429".
                val code = response?.code
                val msg = when (code) {
                    429 -> "Rate limited by the AI provider (HTTP 429). " +
                        "You've sent too many requests in a short window. " +
                        "Wait 30-60 seconds and try again. If this keeps " +
                        "happening, consider switching to a different provider " +
                        "in Settings (Groq's free tier is 30 req/min; NVIDIA's " +
                        "free tier has different limits)."
                    401 -> "Authentication failed (HTTP 401). Your API key is " +
                        "missing, invalid, or expired. Open Settings → AI " +
                        "Configuration and re-enter your API key."
                    403 -> "Forbidden (HTTP 403). Your API key may not have " +
                        "access to the requested model. Check the Model field " +
                        "in Settings → AI Configuration."
                    404 -> "Not found (HTTP 404). The API endpoint URL is " +
                        "wrong. Check the Endpoint field in Settings → AI " +
                        "Configuration — it should end with /chat/completions."
                    500, 502, 503, 504 -> "The AI provider's server errored " +
                        "(HTTP $code). Try again in a few seconds."
                    null -> "Network error: ${t?.message ?: "unknown"}"
                    else -> "SSE failure: HTTP $code — ${t?.message ?: "no detail"}"
                }
                Timber.w(t, msg)
                channel.close(AiException(msg, t))
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }
        })

        awaitClose {
            source.cancel()
        }
    }.flowOn(Dispatchers.IO)

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

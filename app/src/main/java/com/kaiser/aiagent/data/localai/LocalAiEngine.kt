package com.kaiser.aiagent.data.localai

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.File

/**
 * Wraps Google's LiteRT-LM SDK to run Gemma/Qwen models entirely on-device.
 *
 * v0.5.5 fixes:
 *  - Uses MessageCallback.onMessage() for real token-by-token streaming
 *    (was using Flow<Message> which only emitted at the end)
 *  - Properly extracts text from Message.contents instead of toString()
 *  - Shows "Loading model..." status during the 10-30s model load
 *  - 120s timeout for local inference (CPU is much slower than cloud)
 */
class LocalAiEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null

    var lastLoadError: String? = null
        private set

    /** True if on-device AI is supported (Android 12+). */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** True if a model is loaded and ready. */
    fun isModelLoaded(): Boolean = engine?.isInitialized() == true && conversation != null

    fun getLoadedModelPath(): String? = currentModelPath

    /**
     * Loads a .litertlm model file. Takes 10-30 seconds on mid-range devices.
     * Must be called on a background thread.
     */
    suspend fun loadModel(modelPath: String, systemPrompt: String, temperature: Double = 0.7): Boolean {
        if (!isSupported()) return false

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            lastLoadError = "Model file not found: $modelPath"
            return false
        }

        return try {
            close()
            Timber.i("Loading on-device model: %s (%d bytes)", modelPath, modelFile.length())

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = 4096,  // v0.5.7: was 1024, caused "Input token ids too long" because system prompt alone is ~1288 tokens
                maxNumImages = null,
                cacheDir = File(context.cacheDir, "litertlm").absolutePath
            )

            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            engine = newEngine
            currentModelPath = modelPath

            val systemMessage = Message.system(systemPrompt)
            val samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = 0)
            val convConfig = ConversationConfig(
                systemInstruction = null,
                initialMessages = listOf(systemMessage),
                tools = emptyList(),
                samplerConfig = samplerConfig,
                automaticToolCalling = false,
                channels = emptyList(),
                extraContext = emptyMap(),
                loraConfig = null
            )
            conversation = newEngine.createConversation(convConfig)

            Timber.i("On-device model loaded successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load on-device model: %s", e.message)
            lastLoadError = e.message ?: e.javaClass.simpleName
            close()
            false
        }
    }

    /**
     * Sends a user message and streams the response token-by-token.
     *
     * v0.5.6: completely simplified. Removed the racy select{} block
     * that caused "Channel was closed" errors. Now uses a simple
     * for-loop over the channel — suspends when empty, terminates
     * when closed. No select, no race, no crash.
     */
    fun sendMessage(userMessage: String): Flow<String> = flow {
        val conv = conversation
        if (conv == null) {
            throw LocalAiException("No model loaded. Download a model first via Settings → Models.")
        }

        val tokenChannel = Channel<String>(Channel.BUFFERED)
        var inferenceError: Throwable? = null
        var previousText = ""
        // v0.5.8: tracks whether we're inside a <think> block (Qwen3 models)
        val thinkState = ThinkState()

        val userMsg = Message.user(userMessage)

        conv.sendMessageAsync(userMsg, object : MessageCallback {
            override fun onMessage(message: Message) {
                val fullText = extractText(message)
                if (fullText.length > previousText.length) {
                    var delta = fullText.substring(previousText.length)
                    previousText = fullText
                    // v0.5.8: strip Qwen3 <think> tags from output.
                    // Qwen3 models generate <think>reasoning</think> before
                    // the actual answer. The reasoning is often gibberish on
                    // small models and confuses the user. We hide it.
                    delta = stripThinkTags(delta, thinkState)
                    if (delta.isNotEmpty()) {
                        tokenChannel.trySend(delta)
                    }
                }
            }

            override fun onDone() {
                tokenChannel.close()
            }

            override fun onError(t: Throwable) {
                Timber.e(t, "Local AI inference error: %s", t.message)
                inferenceError = t
                tokenChannel.close()
            }
        }, emptyMap())

        // v0.5.6: simple for-loop over the channel. Suspends when empty,
        // terminates when closed. No select{} race condition.
        // Emits each token delta as it arrives.
        for (token in tokenChannel) {
            emit(token)
        }

        // After the channel is closed (onDone or onError), check for errors.
        inferenceError?.let {
            throw LocalAiException(
                "Inference failed: ${it.message ?: it.javaClass.simpleName}",
                it
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Extracts plain text from a Message's Contents.
     * Iterates through Content elements and concatenates all Content.Text items.
     */
    private fun extractText(message: Message): String {
        return try {
            val contents = message.contents
            val contentList = contents.contents  // List<Content>
            val sb = StringBuilder()
            for (content in contentList) {
                if (content is Content.Text) {
                    sb.append(content.text)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract text from message")
            ""
        }
    }

    fun close() {
        try { conversation?.close() } catch (e: Exception) { Timber.w(e, "Error closing conversation") }
        try { engine?.close() } catch (e: Exception) { Timber.w(e, "Error closing engine") }
        conversation = null
        engine = null
        currentModelPath = null
    }
}

/**
 * v0.5.8: Tracks state for stripping <think>...</think> tags from
 * Qwen3 model output. The tags can be split across multiple deltas,
 * so we need to track whether we're currently inside a think block
 * and buffer partial tags.
 */
private class ThinkState {
    var insideThink = false
    var buffer = ""
}

/**
 * Strips <think>...</think> tags from a delta string. Handles tags
 * split across deltas using the [ThinkState] to track position.
 *
 * Returns the cleaned text (may be empty if the entire delta was
 * inside a think block).
 */
private fun stripThinkTags(delta: String, state: ThinkState): String {
    val result = StringBuilder()
    var i = 0
    val text = state.buffer + delta
    state.buffer = ""

    while (i < text.length) {
        if (!state.insideThink) {
            // Look for <think> opening tag
            if (text.startsWith("<think>", i)) {
                state.insideThink = true
                i += 7  // skip "<think>"
            } else if (text.startsWith("<think", i) && i + 6 < text.length) {
                // Partial tag at the end — buffer it
                if (i < text.length) {
                    state.buffer = text.substring(i)
                }
                break
            } else {
                result.append(text[i])
                i++
            }
        } else {
            // Inside think block — look for </think> closing tag
            if (text.startsWith("</think>", i)) {
                state.insideThink = false
                i += 8  // skip "</think>"
            } else if (text.startsWith("</think", i) && i + 7 < text.length) {
                // Partial closing tag — buffer it
                state.buffer = text.substring(i)
                break
            } else {
                // Inside think block — skip this character
                i++
            }
        }
    }

    // Handle remaining partial buffer when not inside think
    if (state.buffer.isEmpty() && !state.insideThink) {
        // Check if there's a partial "<" at the end that could be start of <think>
        val lastLt = text.lastIndexOf('<')
        if (lastLt >= 0 && lastLt > i - 1 && text.length - lastLt < 7) {
            state.buffer = text.substring(lastLt)
            result.delete(result.length - (text.length - lastLt - state.buffer.length).coerceAtLeast(0), result.length)
        }
    }

    return result.toString()
}

class LocalAiException(message: String, cause: Throwable? = null) : Exception(message, cause)

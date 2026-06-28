package com.kaiser.aiagent.data.localai

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Google's LiteRT-LM SDK to run Gemma models entirely on-device.
 *
 * This is the same SDK used by Google's own Edge Gallery app. Models
 * are loaded from .task files (downloaded by [ModelManager]) and run
 * via the native `liblitertlm_jni.so` library.
 *
 * v0.5 supports:
 *   - Loading a .task model file
 *   - Creating a conversation with a system prompt
 *   - Streaming responses via ResponseCallback
 *   - GPU backend on supported devices (falls back to CPU)
 *
 * Requirements:
 *   - Android API 32+ (litertlm native lib requires this)
 *   - At least 4 GB RAM for the E2B model
 *   - ~2 GB free disk space for the model file + inference cache
 *
 * The engine is NOT used on devices below API 32 — the app falls back
 * to the cloud API path in that case.
 */
class LocalAiEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null

    /**
     * Returns true if on-device AI is supported on this device.
     * litertlm requires Android API 32+ (Android 12+).
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Returns true if a model is currently loaded and ready for inference.
     */
    fun isModelLoaded(): Boolean = engine?.isInitialized() == true && conversation != null

    /**
     * Returns the path of the currently loaded model, or null.
     */
    fun getLoadedModelPath(): String? = currentModelPath

    /**
     * Loads a .task model file and prepares a conversation.
     *
     * This is a heavy operation — takes 5-30 seconds depending on model
     * size and device speed. Must be called on a background thread.
     *
     * @param modelPath absolute path to the .task file
     * @param systemPrompt the system prompt to use for all conversations
     * @param temperature sampling temperature (0.0-1.0)
     * @return true if loaded successfully
     */
    suspend fun loadModel(modelPath: String, systemPrompt: String, temperature: Double = 0.7): Boolean {
        if (!isSupported()) {
            Timber.w("LocalAiEngine not supported on API %d (needs 32+)", Build.VERSION.SDK_INT)
            return false
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Timber.e("Model file not found: %s", modelPath)
            return false
        }

        return try {
            // Close any existing engine/conversation first.
            close()

            Timber.i("Loading on-device model: %s (%d bytes)", modelPath, modelFile.length())

            // Create the engine config. Use GPU backend by default —
            // litertlm falls back to CPU automatically if GPU is unavailable.
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = 1024,
                maxNumImages = 0,
                cacheDir = File(context.cacheDir, "litertlm").absolutePath
            )

            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            engine = newEngine
            currentModelPath = modelPath

            // Create a conversation with the system prompt.
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
            Timber.e(e, "Failed to load on-device model")
            close()
            false
        }
    }

    /**
     * Sends a user message and streams the response token-by-token.
     *
     * @param userMessage the user's text input
     * @return a Flow that emits each token as a String
     */
    fun sendMessage(userMessage: String): Flow<String> = flow {
        val conv = conversation
        if (conv == null) {
            throw LocalAiException("No model loaded. Download a model first via Settings → Models.")
        }

        try {
            // Use the Flow-returning overload of sendMessageAsync.
            // It returns Flow<Message> — we collect from it and extract
            // text content from each Message.
            val userMsg = Message.user(userMessage)
            val responseFlow = conv.sendMessageAsync(userMsg, emptyMap())

            responseFlow.collect { message ->
                // Each Message in the flow is a partial/complete response.
                // Extract text content from the message's contents.
                val text = message.toString()  // Message.toString() returns the text
                if (text.isNotEmpty()) {
                    emit(text)
                }
            }
        } catch (e: Exception) {
            throw LocalAiException("Inference failed: ${e.message}", e)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Releases all native resources. Call when switching models or
     * shutting down. Safe to call multiple times.
     */
    fun close() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing conversation")
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing engine")
        }
        conversation = null
        engine = null
        currentModelPath = null
    }
}

class LocalAiException(message: String, cause: Throwable? = null) : Exception(message, cause)

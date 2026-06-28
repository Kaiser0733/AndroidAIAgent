package com.kaiser.aiagent.data.localai

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading, storing, and selecting on-device .task model files.
 *
 * v0.5 downloads models from HuggingFace `litert-community` (same source
 * as Google's Edge Gallery). Models are stored at:
 *   `filesDir/models/<model_id>.task`
 *
 * The manager tracks download progress via a StateFlow so the UI can
 * show a progress bar. Once a model is fully downloaded, it can be
 * loaded by [LocalAiEngine].
 */
class ModelManager(private val context: Context) {

    /** Directory where .task model files are stored. */
    private val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
    }

    private val _downloadProgress = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadProgress: StateFlow<DownloadState> = _downloadProgress.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    /**
     * Returns the local file path for a model, or null if not downloaded.
     */
    fun getModelPath(modelId: String): String? {
        val file = File(modelsDir, "$modelId.task")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /** Returns true if the given model is fully downloaded. */
    fun isDownloaded(modelId: String): Boolean = getModelPath(modelId) != null

    /** Returns the list of model IDs that are downloaded and ready. */
    fun getDownloadedModelIds(): List<String> {
        return modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".task") }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /** Sets the active model (the one that will be loaded by LocalAiEngine). */
    fun setActiveModel(modelId: String) {
        _activeModelId.value = modelId
    }

    /**
     * Downloads a model from HuggingFace. Reports progress via
     * [downloadProgress]. Returns the local file path on success.
     *
     * The download runs on Dispatchers.IO and streams the response body
     * to disk to avoid loading the entire file into memory.
     */
    suspend fun download(model: ModelInfo): String? = withContext(Dispatchers.IO) {
        val targetFile = File(modelsDir, "${model.id}.task")
        val tempFile = File(modelsDir, "${model.id}.task.tmp")

        _downloadProgress.value = DownloadState.Downloading(
            modelId = model.id,
            bytesRead = 0,
            totalBytes = model.sizeBytes,
            percent = 0
        )

        try {
            val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 0  // no read timeout for large downloads
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AndroidAIAgent-ModelManager")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                _downloadProgress.value = DownloadState.Failed(
                    "HTTP $responseCode downloading model. Check your internet connection."
                )
                connection.disconnect()
                return@withContext null
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
            var bytesRead = 0L
            val buffer = ByteArray(64 * 1024)

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        val percent = if (totalBytes > 0) {
                            ((bytesRead * 100) / totalBytes).toInt().coerceAtMost(100)
                        } else 0
                        _downloadProgress.value = DownloadState.Downloading(
                            modelId = model.id,
                            bytesRead = bytesRead,
                            totalBytes = totalBytes,
                            percent = percent
                        )
                    }
                }
            }

            connection.disconnect()

            // Rename temp to final (atomic on most filesystems)
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                _downloadProgress.value = DownloadState.Completed(model.id, targetFile.absolutePath)
                Timber.i("Model %s downloaded to %s (%d bytes)", model.id, targetFile.absolutePath, targetFile.length())
                _activeModelId.value = model.id
                targetFile.absolutePath
            } else {
                _downloadProgress.value = DownloadState.Failed("Downloaded file is empty or missing")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download model %s", model.id)
            tempFile.delete()
            _downloadProgress.value = DownloadState.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /** Deletes a downloaded model file. Returns true if deleted. */
    fun deleteModel(modelId: String): Boolean {
        val file = File(modelsDir, "$modelId.task")
        val deleted = file.delete()
        if (deleted && _activeModelId.value == modelId) {
            _activeModelId.value = null
        }
        return deleted
    }

    /**
     * Returns the device's total RAM in MB. Used by the UI to recommend
     * the best model for the device.
     */
    fun getDeviceRamMb(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    /** Sealed state of a model download. */
    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(
            val modelId: String,
            val bytesRead: Long,
            val totalBytes: Long,
            val percent: Int
        ) : DownloadState()
        data class Completed(val modelId: String, val path: String) : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }
}

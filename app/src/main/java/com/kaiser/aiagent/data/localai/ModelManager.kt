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
     * v0.5.3: uses .litertlm extension (was .task — wrong format).
     */
    fun getModelPath(modelId: String): String? {
        // Check .litertlm (current format)
        val litertlmFile = File(modelsDir, "$modelId.litertlm")
        if (litertlmFile.exists() && litertlmFile.length() > 0) return litertlmFile.absolutePath
        // Also check .task (legacy v0.5.1-v0.5.2 downloads — will be cleaned up)
        val taskFile = File(modelsDir, "$modelId.task")
        if (taskFile.exists() && taskFile.length() > 0) return taskFile.absolutePath
        return null
    }

    /** Returns true if the given model is fully downloaded. */
    fun isDownloaded(modelId: String): Boolean = getModelPath(modelId) != null

    /** Returns the list of model IDs that are downloaded and ready. */
    fun getDownloadedModelIds(): List<String> {
        return modelsDir.listFiles { f -> f.isFile && (f.name.endsWith(".litertlm") || f.name.endsWith(".task")) }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * v0.5.3: deletes old .task files from v0.5.1/v0.5.2 that used the
     * wrong format. Called on app startup to clean up stale downloads.
     */
    fun cleanupOldTaskFiles() {
        modelsDir.listFiles { f -> f.name.endsWith(".task") }?.forEach { f ->
            Timber.i("Cleaning up old .task file: %s", f.name)
            f.delete()
        }
        modelsDir.listFiles { f -> f.name.endsWith(".task.tmp") }?.forEach { f ->
            Timber.i("Cleaning up old .task.tmp partial: %s", f.name)
            f.delete()
        }
    }

    /** Sets the active model (the one that will be loaded by LocalAiEngine). */
    fun setActiveModel(modelId: String) {
        _activeModelId.value = modelId
    }

    /**
     * Downloads a model from HuggingFace. Reports progress via
     * [downloadProgress]. Returns the local file path on success.
     *
     * v0.5.2: **Resumable downloads.** If a previous download was
     * interrupted (network drop, app killed, etc.), the partial .tmp
     * file is preserved. On the next download attempt, the method:
     *   1. Checks if the .tmp file exists and has content
     *   2. Sends an HTTP `Range: bytes=<offset>-` header to resume
     *   3. If the server responds 206 Partial Content, appends to the
     *      existing .tmp file
     *   4. If the server responds 200 OK (no range support), starts fresh
     *
     * This means a 2 GB download that fails at 39% will resume from 39%
     * on the next attempt — no wasted data.
     *
     * The download runs on Dispatchers.IO and streams the response body
     * to disk to avoid loading the entire file into memory.
     */
    suspend fun download(model: ModelInfo): String? = withContext(Dispatchers.IO) {
        val ext = model.fileExtension  // ".litertlm"
        val targetFile = File(modelsDir, "${model.id}$ext")
        val tempFile = File(modelsDir, "${model.id}$ext.tmp")

        // Check for an existing partial download to resume from.
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
        val isResuming = existingBytes > 0

        Timber.i(
            "Starting download of %s (%s resuming from %d bytes)",
            model.id,
            if (isResuming) "RESUMING" else "FRESH",
            existingBytes
        )

        _downloadProgress.value = DownloadState.Downloading(
            modelId = model.id,
            bytesRead = existingBytes,
            totalBytes = model.sizeBytes,
            percent = if (isResuming) ((existingBytes * 100) / model.sizeBytes).toInt() else 0
        )

        try {
            val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 0  // no read timeout for large downloads
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AndroidAIAgent-ModelManager")
                // v0.5.2: request resume from existing partial download
                if (isResuming) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val responseCode = connection.responseCode

            // 206 = Partial Content (resume succeeded)
            // 200 = OK (server ignored Range header — start fresh)
            // Anything else = error
            if (responseCode != 206 && responseCode !in 200..299) {
                _downloadProgress.value = DownloadState.Failed(
                    "HTTP $responseCode downloading model. Check your internet connection."
                )
                connection.disconnect()
                return@withContext null
            }

            // Determine whether we're actually resuming.
            val actuallyResuming = responseCode == 206 && isResuming

            // If the server sent 200 instead of 206, it ignored our Range
            // header — we need to start fresh (truncate the temp file).
            if (isResuming && !actuallyResuming) {
                Timber.i("Server doesn't support resume (got 200, expected 206). Starting fresh.")
                tempFile.delete()
            }

            // Get the total file size. When resuming (206), Content-Length
            // is the REMAINING bytes, not the total. We need to add the
            // already-downloaded bytes to get the total.
            val contentLength = connection.contentLengthLong
            val totalBytes: Long = if (actuallyResuming && contentLength > 0) {
                existingBytes + contentLength
            } else if (contentLength > 0) {
                contentLength
            } else {
                model.sizeBytes
            }

            // Track bytes read. If resuming, start from the existing offset.
            var bytesRead = if (actuallyResuming) existingBytes else 0L
            val buffer = ByteArray(64 * 1024)

            // Open the output file. append=true when resuming, false when fresh.
            val output = FileOutputStream(tempFile, actuallyResuming)
            connection.inputStream.use { input ->
                output.use { out ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
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

            // Verify the downloaded file size matches the expected total.
            if (tempFile.length() < totalBytes) {
                _downloadProgress.value = DownloadState.Failed(
                    "Download incomplete: got ${tempFile.length()} of $totalBytes bytes. " +
                        "The partial file has been saved — tap Download again to resume."
                )
                return@withContext null
            }

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
            Timber.e(e, "Failed to download model %s (partial file preserved at %d bytes)", model.id, tempFile.length())
            // v0.5.2: DON'T delete the temp file on failure — preserve it
            // so the next download attempt can resume from where we left off.
            _downloadProgress.value = DownloadState.Failed(
                "Download failed at ${tempFile.length()}/${model.sizeBytes} bytes " +
                    "(${if (model.sizeBytes > 0) ((tempFile.length() * 100) / model.sizeBytes).toInt() else 0}%). " +
                    "Tap Download again to resume from ${tempFile.length()} bytes — no data wasted."
            )
            null
        }
    }

    /**
     * Returns the size of the partial download (if any) for a model.
     * Returns 0 if no partial download exists.
     */
    fun getPartialDownloadBytes(modelId: String): Long {
        val tempFile = File(modelsDir, "$modelId.litertlm.tmp")
        if (tempFile.exists()) return tempFile.length()
        val oldTemp = File(modelsDir, "$modelId.task.tmp")
        return if (oldTemp.exists()) oldTemp.length() else 0L
    }

    /**
     * Returns true if a partial download exists for the given model.
     */
    fun hasPartialDownload(modelId: String): Boolean = getPartialDownloadBytes(modelId) > 0

    /** Deletes a downloaded model file. Returns true if deleted. */
    fun deleteModel(modelId: String): Boolean {
        var deleted = false
        File(modelsDir, "$modelId.litertlm").let { if (it.exists()) deleted = it.delete() }
        File(modelsDir, "$modelId.litertlm.tmp").let { if (it.exists()) it.delete() }
        File(modelsDir, "$modelId.task").let { if (it.exists()) { it.delete(); deleted = true } }
        File(modelsDir, "$modelId.task.tmp").let { if (it.exists()) it.delete() }
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

package com.kaiser.aiagent.data.logging

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralised access to on-device log files.
 *
 * v0.1 layout (under `filesDir/logs/`):
 *   - `app.log`        — rolling application log (written by [FileLogger])
 *   - `crash.log`      — last uncaught exception stack trace
 *   - `update.log`     — updater activity (download attempts, failures, etc.)
 *
 * The repository is intentionally minimal: it exposes helpers to append
 * structured log lines and to read the most recent content back for the
 * in-app log viewer. A future version can swap the storage to a proper
 * on-device SQLite or Room-backed log store without changing call sites.
 */
class LogRepository(private val context: Context) {

    private val logDir: File by lazy {
        File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
    }

    val appLogFile: File get() = File(logDir, "app.log")
    val crashLogFile: File get() = File(logDir, "crash.log")
    val updateLogFile: File get() = File(logDir, "update.log")

    private val isoFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** Append a single line to the application log file. */
    fun appendAppLog(level: String, tag: String, message: String) {
        append(appLogFile, "[$level] ${isoFormat.format(Date())} $tag: $message")
    }

    /** Append a line to the dedicated update log. */
    fun appendUpdateLog(message: String) {
        append(updateLogFile, "[UPDATE] ${isoFormat.format(Date())} $message")
    }

    /** Persist a throwable as the latest crash dump. */
    fun writeCrash(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val header = "==== Crash at ${isoFormat.format(Date())} ====\n"
        val body = sw.toString()
        crashLogFile.writeText(header + body)
        // Also mirror to the main app log so it shows up in the combined view.
        appendAppLog("E", "Crash", throwable.javaClass.name + ": " + throwable.message)
    }

    /** Read the last `maxBytes` bytes of a log file as a UTF-8 string. */
    fun readTail(file: File, maxBytes: Long = 64 * 1024): String {
        if (!file.exists()) return ""
        val length = file.length()
        if (length <= maxBytes) return file.readText()
        return file.useLines { lines ->
            // Drop leading partial line, keep the rest.
            val sb = StringBuilder()
            val skipBytes = length - maxBytes
            var skipped = 0L
            for (line in lines) {
                if (skipped < skipBytes) {
                    skipped += line.length + 1
                    continue
                }
                sb.appendLine(line)
            }
            sb.toString()
        }
    }

    private fun append(target: File, line: String) {
        try {
            target.appendText(line + "\n")
            // Trivial rolling: keep each log under 1 MB by truncating the head
            // when the file grows too large. A future version can swap this
            // for a proper rotating file handler.
            if (target.length() > MAX_LOG_BYTES) {
                val tail = readTail(target, MAX_LOG_BYTES / 2)
                target.writeText(tail)
            }
        } catch (e: Exception) {
            // Logging must never crash the app — swallow and report to logcat.
            Timber.e(e, "Failed to append to log file ${target.name}")
        }
    }

    companion object {
        private const val MAX_LOG_BYTES = 1L * 1024 * 1024 // 1 MB
    }
}

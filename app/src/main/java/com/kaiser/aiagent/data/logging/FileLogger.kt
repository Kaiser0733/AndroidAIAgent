package com.kaiser.aiagent.data.logging

import android.content.Context
import timber.log.Timber
import java.util.Date

/**
 * Bridges Timber log calls into [LogRepository.appendAppLog], so every
 * `Timber.x(...)` call is mirrored to a rolling on-device file in addition
 * to logcat. Plant it once from [com.kaiser.aiagent.AndroidAIAgentApp].
 */
class FileLogger(private val context: Context) {

    private val repository = LogRepository(context)

    /**
     * Returns a Timber [Timber.Tree] that forwards log calls to the file
     * repository. Level mapping matches Android's `Log` constants.
     */
    fun asTree(): Timber.Tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val level = when (priority) {
                android.util.Log.VERBOSE -> "V"
                android.util.Log.DEBUG -> "D"
                android.util.Log.INFO -> "I"
                android.util.Log.WARN -> "W"
                android.util.Log.ERROR -> "E"
                android.util.Log.ASSERT -> "A"
                else -> "?"
            }
            val safeTag = tag ?: "App"
            val fullMessage = if (t != null) {
                "$message\n${android.util.Log.getStackTraceString(t)}"
            } else {
                message
            }
            repository.appendAppLog(level, safeTag, fullMessage)
        }
    }
}

package com.kaiser.aiagent.data.logging

/**
 * Installs a global [Thread.UncaughtExceptionHandler] that persists the
 * last crash to disk before re-throwing to the default handler. The dump
 * can be inspected later from the in-app log viewer or via `adb pull`.
 *
 * This is intentionally simple — we do not attempt to restart the process
 * or upload the crash anywhere. A future release may add remote crash
 * reporting (e.g. Sentry / custom endpoint) gated behind user opt-in.
 */
object CrashLogger {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installed = false

    @Synchronized
    fun install(repository: LogRepository) {
        if (installed) return
        installed = true
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                repository.writeCrash(throwable)
                repository.appendUpdateLog(
                    "Crash in thread ${thread.name}: ${throwable.javaClass.name}"
                )
            } catch (_: Throwable) {
                // Never throw inside the crash handler.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

package com.kaiser.aiagent

import android.app.Application
import com.kaiser.aiagent.data.logging.CrashLogger
import com.kaiser.aiagent.data.logging.FileLogger
import com.kaiser.aiagent.data.logging.LogRepository
import com.kaiser.aiagent.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

/**
 * Application entry point.
 *
 * Responsibilities at v0.1:
 *  - Initialise the logging subsystem (Timber + file logger + crash handler).
 *  - Bootstrap the Koin dependency-injection graph.
 *
 * Future versions will also initialise the agent runtime (chat, tools, memory,
 * automation, accessibility) from here.
 */
class AndroidAIAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // ---- Logging -----------------------------------------------------
        // Plant a debug tree that writes to logcat, plus a custom tree that
        // mirrors every log line into a rolling file under app files dir.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        val fileLogger = FileLogger(this)
        Timber.plant(fileLogger.asTree())

        // Wire a global crash handler that dumps the stack trace to a file
        // before the process dies, so post-mortem analysis is possible.
        val logRepository = LogRepository(this)
        CrashLogger.install(logRepository)

        // ---- Dependency injection ---------------------------------------
        startKoin {
            androidContext(this@AndroidAIAgentApp)
            modules(appModule)
        }

        Timber.i("AndroidAIAgentApp initialised — version=${BuildConfig.VERSION_NAME}")
    }
}

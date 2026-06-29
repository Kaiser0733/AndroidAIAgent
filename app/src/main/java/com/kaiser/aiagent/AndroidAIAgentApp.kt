package com.kaiser.aiagent

import android.app.Application
import com.kaiser.aiagent.data.logging.CrashLogger
import com.kaiser.aiagent.data.logging.FileLogger
import com.kaiser.aiagent.data.logging.LogRepository
import com.kaiser.aiagent.di.agentModule
import com.kaiser.aiagent.di.aiModule
import com.kaiser.aiagent.di.appModule
import com.kaiser.aiagent.di.chatModule
import com.kaiser.aiagent.di.memoryModule
import com.kaiser.aiagent.di.registerAllTools
import com.kaiser.aiagent.di.toolsModule
import com.kaiser.aiagent.domain.tools.ToolRegistry
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

/**
 * Application entry point.
 *
 * v0.1: initialised the logging subsystem and Koin.
 * v0.2: unchanged.
 * v0.3: registers all demo tools with the [ToolRegistry] after Koin
 * starts, so the agent can find them at runtime.
 */
class AndroidAIAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // ---- Logging -----------------------------------------------------
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        val fileLogger = FileLogger(this)
        Timber.plant(fileLogger.asTree())
        val logRepository = LogRepository(this)
        CrashLogger.install(logRepository)

        // ---- Dependency injection ---------------------------------------
        startKoin {
            androidContext(this@AndroidAIAgentApp)
            modules(
                appModule,
                aiModule,
                agentModule,
                chatModule,
                memoryModule,
                toolsModule
            )
        }

        // ---- Tool registration (v0.3) -----------------------------------
        // After Koin is up, register every tool singleton with the
        // registry so the agent can describe and invoke them.
        val registry: ToolRegistry = get()
        registerAllTools(registry)

        Timber.i("AndroidAIAgentApp initialised — v${BuildConfig.VERSION_NAME}")
        Timber.i("Registered ${registry.all().size} tools: ${registry.all().joinToString { it.name }}")
    }
}

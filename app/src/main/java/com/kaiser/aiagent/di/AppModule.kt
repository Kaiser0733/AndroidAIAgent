package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.logging.LogRepository
import com.kaiser.aiagent.data.remote.RemoteConfigRepository
import com.kaiser.aiagent.data.updater.UpdateRepository
import com.kaiser.aiagent.ui.screens.about.AboutViewModel
import com.kaiser.aiagent.ui.screens.home.HomeViewModel
import com.kaiser.aiagent.ui.screens.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Foundation Koin module — repositories + view models that existed in
 * v0.1/v0.2. New v0.3 subsystems live in their own modules:
 *  - [aiModule]        — AI HTTP client + settings + DebugViewModel
 *  - [agentModule]     — AgentRuntime + ToolRegistry + ToolExecutor
 *  - [chatModule]      — ConversationRepository + ChatViewModel
 *  - [memoryModule]    — MemoryRepository + MemoryManager
 *  - [toolsModule]     — demo tool singletons
 *
 * All modules are aggregated in
 * [com.kaiser.aiagent.AndroidAIAgentApp.onCreate].
 */
val appModule = module {

    // ---- Logging ---------------------------------------------------------
    single { LogRepository(androidContext()) }

    // ---- Remote configuration -------------------------------------------
    single { RemoteConfigRepository(androidContext()) }

    // ---- Updater ---------------------------------------------------------
    single { UpdateRepository(androidContext(), get()) }

    // ---- View models (foundation) --------------------------------------
    viewModel { HomeViewModel(get(), get()) }
    viewModel { SettingsViewModel(androidContext(), get(), get()) }
    viewModel { AboutViewModel(androidContext()) }
}

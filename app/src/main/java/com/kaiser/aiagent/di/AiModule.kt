package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.ai.AiService
import com.kaiser.aiagent.data.ai.AiSettings
import com.kaiser.aiagent.data.ai.DataStoreAiSettings
import com.kaiser.aiagent.data.localai.LocalAiEngine
import com.kaiser.aiagent.data.localai.ModelManager
import com.kaiser.aiagent.ui.screens.debug.DebugViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for the AI subsystem.
 *
 * v0.5 adds [LocalAiEngine] and [ModelManager] for on-device inference.
 */
val aiModule = module {
    single<AiSettings> { DataStoreAiSettings(androidContext()) }
    single { AiService() }
    single { LocalAiEngine(androidContext()) }
    single { ModelManager(androidContext()) }
    single { AiRepository(get(), get(), get()) }
    viewModel { DebugViewModel(get(), get(), get(), get()) }
    viewModel { com.kaiser.aiagent.ui.screens.models.ModelsViewModel(get(), get()) }
}

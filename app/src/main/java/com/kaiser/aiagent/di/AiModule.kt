package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.ai.AiRepository
import com.kaiser.aiagent.data.ai.AiService
import com.kaiser.aiagent.data.ai.AiSettings
import com.kaiser.aiagent.data.ai.DataStoreAiSettings
import com.kaiser.aiagent.ui.screens.debug.DebugViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for the AI subsystem.
 *
 *  - [DataStoreAiSettings] persists API key / endpoint / model in
 *    app-private DataStore.
 *  - [AiService] is a stateless HTTP client — created once, reused.
 *  - [AiRepository] wraps the service with config resolution.
 */
val aiModule = module {
    single<AiSettings> { DataStoreAiSettings(androidContext()) }
    single { AiService() }
    single { AiRepository(get(), get()) }
    viewModel { DebugViewModel(get(), get(), get(), get()) }
}

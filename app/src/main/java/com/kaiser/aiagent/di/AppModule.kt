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
 * Koin module wiring for v0.1.
 *
 * Each future agent module (chat / tools / memory / automation /
 * accessibility / updater) is expected to contribute its own Koin module
 * later and be aggregated here. For now we only register the foundation
 * pieces: repositories + view models.
 */
val appModule = module {

    // ---- Logging ---------------------------------------------------------
    single { LogRepository(androidContext()) }

    // ---- Remote configuration -------------------------------------------
    single { RemoteConfigRepository(androidContext()) }

    // ---- Updater ---------------------------------------------------------
    single { UpdateRepository(androidContext(), get()) }

    // ---- View models -----------------------------------------------------
    viewModel { HomeViewModel(get(), get()) }
    viewModel { SettingsViewModel(androidContext(), get()) }
    viewModel { AboutViewModel(androidContext()) }
}

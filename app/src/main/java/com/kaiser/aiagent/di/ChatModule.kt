package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.chat.ConversationRepository
import com.kaiser.aiagent.ui.screens.chat.ChatViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for the chat subsystem.
 */
val chatModule = module {
    single { ConversationRepository(androidContext()) }
    viewModel { ChatViewModel(get(), get()) }
}

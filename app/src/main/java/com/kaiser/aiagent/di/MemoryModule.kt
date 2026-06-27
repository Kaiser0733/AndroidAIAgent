package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.memory.MemoryRepository
import com.kaiser.aiagent.memory.MemoryManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin module for the memory subsystem.
 */
val memoryModule = module {
    single { MemoryRepository(androidContext()) }
    single { MemoryManager(get()) }
}

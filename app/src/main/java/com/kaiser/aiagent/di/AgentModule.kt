package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.logging.LogRepository
import com.kaiser.aiagent.domain.agent.AgentRuntime
import com.kaiser.aiagent.domain.tools.PermissionManager
import com.kaiser.aiagent.domain.tools.ToolExecutor
import com.kaiser.aiagent.domain.tools.ToolRegistry
import org.koin.dsl.module

/**
 * Koin module for the agent runtime + tool framework.
 *
 * v0.4: [ToolExecutor] now requires [PermissionManager] — every tool
 * execution goes through the permission check (SAFE auto-grants,
 * CONFIRMATION_REQUIRED suspends, BLOCKED auto-denies).
 */
val agentModule = module {
    single { ToolRegistry() }
    single { ToolExecutor(get(), get()) }
    single { AgentRuntime(get(), get(), get(), get<LogRepository>()) }
}

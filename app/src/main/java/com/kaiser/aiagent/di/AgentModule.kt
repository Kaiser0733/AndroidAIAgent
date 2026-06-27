package com.kaiser.aiagent.di

import com.kaiser.aiagent.domain.agent.AgentRuntime
import com.kaiser.aiagent.domain.tools.ToolExecutor
import com.kaiser.aiagent.domain.tools.ToolRegistry
import org.koin.dsl.module

/**
 * Koin module for the agent runtime + tool framework.
 *
 *  - [ToolRegistry] is a singleton — tools register themselves at app
 *    startup (see [toolsModule]).
 *  - [ToolExecutor] is a singleton — wraps the registry.
 *  - [AgentRuntime] is a singleton — stateless across turns (its
 *    StateFlow resets per turn).
 */
val agentModule = module {
    single { ToolRegistry() }
    single { ToolExecutor(get()) }
    single { AgentRuntime(get(), get(), get()) }
}

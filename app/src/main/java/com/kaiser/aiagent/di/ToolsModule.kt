package com.kaiser.aiagent.di

import com.kaiser.aiagent.domain.tools.ToolRegistry
import com.kaiser.aiagent.tools.demo.AppInfoTool
import com.kaiser.aiagent.tools.demo.DeviceInfoTool
import com.kaiser.aiagent.tools.demo.GetTimeTool
import org.koin.dsl.module

/**
 * Koin module that registers all v0.3 demo tools with the [ToolRegistry].
 *
 * Future tool modules (FileTools, AccessibilityTools, AutomationTools,
 * WorkflowEngine) will each contribute their own module that registers
 * additional tools here. They are NOT registered at v0.3.
 */
val toolsModule = module {
    single<GetTimeTool> { GetTimeTool() }
    single<AppInfoTool> { AppInfoTool() }
    single<DeviceInfoTool> { DeviceInfoTool() }
}

/**
 * Helper invoked from [com.kaiser.aiagent.AndroidAIAgentApp.onCreate]
 * to register all tool singletons into the [ToolRegistry] after Koin
 * has started.
 */
fun registerTools(registry: ToolRegistry) {
    registry.register(GetTimeTool())
    registry.register(AppInfoTool())
    registry.register(DeviceInfoTool())
}

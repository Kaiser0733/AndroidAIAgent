package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.PermissionManager
import com.kaiser.aiagent.domain.tools.ToolRegistry
import com.kaiser.aiagent.tools.accessibility.GoBackTool
import com.kaiser.aiagent.tools.accessibility.GoHomeTool
import com.kaiser.aiagent.tools.accessibility.ReadScreenTool
import com.kaiser.aiagent.tools.accessibility.ScrollTool
import com.kaiser.aiagent.tools.accessibility.TapTextTool
import com.kaiser.aiagent.tools.accessibility.TypeTextTool
import com.kaiser.aiagent.tools.accessibility.WaitSecondsTool
import com.kaiser.aiagent.tools.blocked.AppControlTool
import com.kaiser.aiagent.tools.blocked.DeleteFileTool
import com.kaiser.aiagent.tools.blocked.MoveFileTool
import com.kaiser.aiagent.tools.blocked.RenameFileTool
import com.kaiser.aiagent.tools.demo.AppInfoTool
import com.kaiser.aiagent.tools.demo.DeviceInfoTool
import com.kaiser.aiagent.tools.demo.GetTimeTool
import com.kaiser.aiagent.tools.file.CreateFolderTool
import com.kaiser.aiagent.tools.file.CreateTextFileTool
import com.kaiser.aiagent.tools.file.FileInfoTool
import com.kaiser.aiagent.tools.file.ListFilesTool
import com.kaiser.aiagent.tools.file.ListStorageRootsTool
import com.kaiser.aiagent.tools.file.ReadTextFileTool
import com.kaiser.aiagent.tools.file.SearchFilesTool
import com.kaiser.aiagent.tools.memory.SearchMemoryTool
import com.kaiser.aiagent.tools.system.OpenAppTool
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.dsl.module

/**
 * v0.6.4 registered tools (22 total):
 *   SAFE (20):
 *     - get_time, app_info, device_info
 *     - list_storage_roots, list_files, search_files, file_info,
 *       read_text_file, search_memory
 *     - open_app
 *     - read_screen, tap_text, type_text, scroll, go_back, go_home
 *     - wait_seconds (NEW v0.6.4)
 *   CONFIRMATION_REQUIRED (2):
 *     - create_folder, create_text_file
 *   BLOCKED (4):
 *     - delete_file, move_file, rename_file, app_control
 */
val toolsModule = module {
    single { StorageRepository(androidContext()) }
    single { PermissionManager() }

    // Demo tools
    single { GetTimeTool() }
    single { AppInfoTool() }
    single { DeviceInfoTool() }
    // File tools (SAFE)
    single { ListStorageRootsTool(get()) }
    single { ListFilesTool(get()) }
    single { SearchFilesTool(get()) }
    single { FileInfoTool(get()) }
    single { ReadTextFileTool(get()) }
    // File tools (CONFIRMATION_REQUIRED)
    single { CreateFolderTool(get()) }
    single { CreateTextFileTool(get()) }
    // Memory tool
    single { SearchMemoryTool(get()) }
    // BLOCKED placeholders
    single { DeleteFileTool() }
    single { MoveFileTool() }
    single { RenameFileTool() }
    single { AppControlTool() }
    // v0.6: System tools
    single { OpenAppTool(androidContext()) }
    // v0.6.2: Accessibility tools (require AgentAccessibilityService running)
    single { ReadScreenTool() }
    single { TapTextTool() }
    single { TypeTextTool() }
    single { ScrollTool() }
    single { GoBackTool() }
    single { GoHomeTool() }
    // v0.6.4: explicit wait tool
    single { WaitSecondsTool() }
}

/**
 * Invoked from [com.kaiser.aiagent.AndroidAIAgentApp.onCreate] to
 * register all tool singletons (resolved via Koin) into the
 * [ToolRegistry]. This must be called AFTER `startKoin`.
 */
fun registerAllTools(registry: ToolRegistry) {
    val koin = GlobalContext.get()
    val tools: List<AgentTool> = listOf(
        koin.get<GetTimeTool>(),
        koin.get<AppInfoTool>(),
        koin.get<DeviceInfoTool>(),
        koin.get<ListStorageRootsTool>(),
        koin.get<ListFilesTool>(),
        koin.get<SearchFilesTool>(),
        koin.get<FileInfoTool>(),
        koin.get<ReadTextFileTool>(),
        koin.get<CreateFolderTool>(),
        koin.get<CreateTextFileTool>(),
        koin.get<SearchMemoryTool>(),
        koin.get<DeleteFileTool>(),
        koin.get<MoveFileTool>(),
        koin.get<RenameFileTool>(),
        koin.get<AppControlTool>(),
        koin.get<OpenAppTool>(),
        koin.get<ReadScreenTool>(),
        koin.get<TapTextTool>(),
        koin.get<TypeTextTool>(),
        koin.get<ScrollTool>(),
        koin.get<GoBackTool>(),
        koin.get<GoHomeTool>(),
        koin.get<WaitSecondsTool>()
    )
    for (tool in tools) {
        registry.register(tool)
    }
}

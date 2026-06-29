package com.kaiser.aiagent.di

import com.kaiser.aiagent.data.storage.StorageRepository
import com.kaiser.aiagent.tools.file.CreateFolderTool
import com.kaiser.aiagent.tools.file.CreateTextFileTool
import com.kaiser.aiagent.tools.file.FileInfoTool
import com.kaiser.aiagent.tools.file.ListFilesTool
import com.kaiser.aiagent.tools.file.ListStorageRootsTool
import com.kaiser.aiagent.tools.file.ReadTextFileTool
import com.kaiser.aiagent.tools.file.SearchFilesTool
import com.kaiser.aiagent.tools.memory.SearchMemoryTool
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin module for v0.4 file tools + storage layer.
 *
 *  - [StorageRepository] is a singleton — the single auditable entry
 *    point for all device storage access.
 *  - File tools are singletons — they're stateless and can be reused
 *    across agent turns.
 *
 * The tools themselves are registered with the [ToolRegistry] in
 * [registerTools] (see [ToolsModule]) — this module only declares
 * the singleton instances.
 */
val fileToolsModule = module {
    single { StorageRepository(androidContext()) }
    single { ListStorageRootsTool(get()) }
    single { ListFilesTool(get()) }
    single { SearchFilesTool(get()) }
    single { FileInfoTool(get()) }
    single { ReadTextFileTool(get()) }
    single { CreateFolderTool(get()) }
    single { CreateTextFileTool(get()) }
    single { SearchMemoryTool(get()) }
}

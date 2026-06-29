package com.kaiser.aiagent.domain.tools

/**
 * Permission level required to execute a tool. Controls how the
 * [PermissionManager] handles a tool-call request from the model.
 *
 * v0.4 ships tools at all three levels:
 *   - SAFE: get_time, app_info, device_info, list_storage_roots,
 *     list_files, search_files, file_info, read_text_file, search_memory
 *   - CONFIRMATION_REQUIRED: create_folder, create_text_file
 *   - BLOCKED: (no v0.4 tools are blocked — but the level exists so
 *     future dangerous tools can be registered but disabled by policy)
 */
enum class ToolPermissionLevel {
    /** Execute immediately without user interaction. */
    SAFE,

    /**
     * Pause execution and ask the user via a confirmation dialog.
     * Execute only after explicit approval. If the user denies, return
     * an error result.
     */
    CONFIRMATION_REQUIRED,

    /**
     * Never execute. Return an error result immediately. Used for tools
     * that are registered (so the Debug screen lists them) but disabled
     * by policy or by the user.
     */
    BLOCKED
}

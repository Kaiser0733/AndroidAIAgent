# Changelog

All notable changes to this project are documented in this file. The
format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/).

## [0.3.1] - 2026-06-27

### Changed
- **Default AI endpoint switched from GLM-4 (paid) to NVIDIA NIM (free
  tier at https://build.nvidia.com).** This makes the app usable out
  of the box without paying — sign up at
  https://build.nvidia.com/deepseek-ai/deepseek-v4-pro with an email
  or Google account, click "Get API Key", paste it into Settings.
- Default model is now `deepseek-ai/deepseek-v4-pro`.
- Default `extraBody` is `{"chat_template_kwargs":{"thinking":false}}`
  so DeepSeek's reasoning trace is suppressed by default (the user
  sees the final answer, not the thinking process).

### Added
- `top_p` parameter on `AiRequest` and `AiConfig` — sent as
  `top_p` in the request body when set (optional).
- `extraBody` field on `AiConfig` — a raw JSON object string that is
  merged into the top level of every request body. Lets the user set
  provider-specific options (like NVIDIA DeepSeek's
  `chat_template_kwargs.thinking`) without code changes.
- `AiRequest.toJsonWithExtraBody()` helper — serializes the request
  and merges the extra body, falling back to plain serialization when
  the extra body is blank.
- Settings screen now exposes Temperature, Top P, Max tokens, and
  Extra body JSON fields (the last with a placeholder showing the
  DeepSeek thinking-toggle example).
- DataStoreAiSettings persists `top_p` and `extra_body` keys.

### Documentation
- `AiConfig.kt` companion now documents alternative free endpoints
  inline: Groq, Google Gemini, OpenRouter, GLM-4, local Llama.cpp /
  Ollama — with their endpoints, default models, and where to get
  API keys.

### Tags
- Git tag `v0.3.1-alpha` is created on the `dev` branch (and
  fast-forwarded to `main`). GitHub Release `v0.3.1-alpha` is published
  with the APK as a release asset, marked as `make_latest=true`.
  The previous `v0.3-alpha` release is demoted to `make_latest=false`.

## [0.3] - 2026-06-27

### Added — AI module
- `data/ai/AiConfig.kt` — immutable config (apiKey, endpoint, model,
  temperature, maxTokens, timeouts, retry policy).
- `data/ai/AiSettings.kt` + `DataStoreAiSettings.kt` — DataStore-backed
  persistence (app-private; API key never logged or committed).
- `data/ai/AiModels.kt` — OpenAI-compatible request/response DTOs
  (`AiRequest`, `AiResponse`, `AiMessage`, `AiChoice`, `AiDelta`,
  `AiUsage`, `AiError`).
- `data/ai/AiService.kt` — OkHttp + OkHttp-SSE client. Streaming via
  `EventSources`, non-streaming with retry+backoff on 429/5xx/IO.
- `data/ai/AiRepository.kt` — high-level façade. Resolves config from
  `AiSettings`, exposes `chat()`, `streamChat()`, and `testConnection()`.

### Added — Tool framework
- `domain/tools/AgentTool.kt` — interface every tool implements.
- `domain/tools/ToolCall.kt` + `ToolCallParser.kt` — standard JSON
  tool-call format `{"tool":"...","arguments":{}}` and a permissive
  parser that finds tool calls inside prose / code fences.
- `domain/tools/ToolRegistry.kt` — mutable registry; renders the tool
  catalog into the system prompt.
- `domain/tools/ToolExecutor.kt` — runs tool calls, catches all
  exceptions, returns `ToolResult` (always JSON-serializable).

### Added — Demo tools (3, all read-only)
- `tools/demo/GetTimeTool.kt` — returns current local time in ISO 8601.
- `tools/demo/AppInfoTool.kt` — returns app version, build number,
  application ID, build type.
- `tools/demo/DeviceInfoTool.kt` — returns Android version, SDK level,
  manufacturer, model, ABIs.

### Added — Agent runtime
- `domain/agent/AgentContext.kt` — per-turn working state + system
  prompt builder (embeds the tool catalog and tool-call format spec).
- `domain/agent/AgentState.kt` — UI-facing snapshot (busy,
  streamingText, lastToolCall, lastToolResult, toolCallsThisTurn,
  lastError).
- `domain/agent/AgentRuntime.kt` — multi-turn loop: stream → detect
  tool call → execute → append tool result → repeat. Caps at 5 tool
  iterations per turn (anti-loop).

### Added — Chat system
- `data/chat/ConversationEntity.kt` + `MessageEntity.kt` + `MessageRole`
  — persisted DTOs.
- `data/chat/ConversationRepository.kt` — file-backed JSON store at
  `filesDir/conversations/{id}.json`. CRUD + auto-title from first
  user message.
- `ui/screens/chat/ChatViewModel.kt` — owns active conversation,
  streams assistant responses, persists user+assistant messages.
- `ui/screens/chat/ChatScreen.kt` — message list with bubbles,
  streaming indicator, copy action, new / delete conversation
  actions, input bar with send button.
- `ui/screens/chat/MarkdownText.kt` — minimal Markdown renderer
  (headers, bold, italic, inline code, fenced code blocks, bullets,
  numbered lists, blockquotes).

### Added — Memory foundation
- `data/memory/MemoryEntry.kt` — persisted DTO (id, type, content,
  source, createdAt, tags).
- `data/memory/MemoryRepository.kt` — file-backed JSON store at
  `filesDir/memory/memories.json`. CRUD + StateFlow of entries.
- `memory/MemoryManager.kt` — policy layer over the repository;
  validates types (fact / preference / note). v0.3 supports only
  manual storage; auto-extraction is a future concern.

### Added — Debug screen (hidden)
- `ui/screens/debug/DebugViewModel.kt` — aggregates snapshots of AI
  config, agent state, registered tools, memory count, API status.
- `ui/screens/debug/DebugScreen.kt` — reachable from Settings →
  "Debug" button. Shows live runtime state, registered tool list,
  and a "Test connection" button.

### Added — Settings (AI section)
- Settings screen now has an "AI Configuration" section with fields
  for API Key (masked), Endpoint, Model, and a "Test Connection"
  button. Saved via DataStore; the AiService picks up changes
  immediately (no app restart needed).

### Added — Navigation
- `Destinations.CHAT` and `Destinations.DEBUG` added to the nav graph.
- Home screen has a new primary "Open Chat" button.

### Added — DI modules
- `di/AiModule.kt`, `di/AgentModule.kt`, `di/ChatModule.kt`,
  `di/MemoryModule.kt`, `di/ToolsModule.kt` — split out from
  `AppModule.kt` for clarity. All registered in
  `AndroidAIAgentApp.onCreate`.

### Added — Future placeholders
- `tools/future/FileToolsPlaceholder.kt`
- `tools/future/AccessibilityToolsPlaceholder.kt`
- `tools/future/AutomationToolsPlaceholder.kt`
- `tools/future/WorkflowEnginePlaceholder.kt`

Each contains a KDoc explaining the planned responsibilities. None
are registered with the `ToolRegistry` at v0.3.

### Changed
- Bumped `versionCode` 2 → 3, `versionName` "0.2" → "0.3".
- Added `okhttp-sse` 4.12.0 dependency for streaming.
- `AndroidAIAgentApp.onCreate` now calls `registerTools(registry)`
  after Koin starts to populate the `ToolRegistry`.
- `HomeScreen` now takes `onOpenChat` callback.
- `SettingsScreen` now takes `onOpenDebug` callback.
- `SettingsViewModel` constructor now takes `AiRepository`.

### Tags
- Git tag `v0.3-alpha` is created on the `dev` branch (and
  fast-forwarded to `main`). GitHub Release `v0.3-alpha` is published
  with the APK as a release asset, marked as `make_latest=true`.

## [0.2] - 2026-06-27

### Added
- **Update Available dialog Download button is now wired up.** Tapping
  it calls `UpdateRepository.downloadAndInstall()` which downloads the
  APK from the GitHub release asset URL and hands off to the Android
  system package installer via a FileProvider content URI.
- `HomeViewModel.downloadAndInstall()` — coordinates the download on
  `Dispatchers.IO`, surfaces a progress indicator in the dialog, and
  emits a toast on success or failure.
- `HomeViewModel.dismissUpdateDialog()` — dismisses the update dialog
  without downloading.

### Changed
- Bumped `versionCode` 1 → 2 and `versionName` "0.1" → "0.2" so the
  v0.1 installer can detect v0.2 as a real update via the GitHub
  Releases API.

### Fixed
- `RemoteConfigRepository` — added `@OptIn(ExperimentalSerializationApi)`
  around `explicitNulls = false` to silence the opt-in warning.
- `HomeScreen` — switched `Icons.Filled.Chat` to
  `Icons.AutoMirrored.Filled.Chat` (the former is deprecated).

### Tags
- Git tag `v0.2-alpha` is created on the `dev` branch (and fast-forwarded
  to `main`). GitHub Release `v0.2-alpha` is published with the APK as a
  release asset, marked as `make_latest=true` so the app's
  `releases/latest` API call returns v0.2.

## [0.1] - 2026-06-27

### Added
- Application shell: launch, home, settings, and about screens built
  with Jetpack Compose and Material 3.
- Single-activity host using Compose Navigation.
- MVVM + Repository architecture with Koin dependency injection.
- Update system:
  - GitHub Releases API client (`GET /repos/{owner}/{name}/releases/latest`).
  - Dotted-version comparator with mixed numeric/lexicographic segments.
  - ABI-aware APK asset selection (arm64-v8a, armeabi-v7a, x86_64,
    universal fallback).
  - APK download to `externalCacheDir/updates/` and hand-off to the
    system package installer via `FileProvider`.
  - In-app "Update Available" dialog with release notes.
  - Graceful error handling — every step writes to `logs/update.log`
    and surfaces a user-visible toast on failure.
- Remote configuration system:
  - JSON schema with `latestVersion`, `minimumVersion`, `disableAgent`,
    `maintenanceMode`, `maintenanceMessage`.
  - Configurable URL persisted in DataStore.
  - Offline caching of the last successful fetch.
- Logging system:
  - `logs/app.log` rolling application log (1 MB cap).
  - `logs/crash.log` last uncaught exception dump.
  - `logs/update.log` dedicated updater activity log.
  - Timber tree that mirrors every log call to file.
  - Global `UncaughtExceptionHandler` that persists crashes before the
    process dies.
- Architecture placeholders for future modules under `future/`:
  `chat`, `tools`, `memory`, `automation`, `accessibility`, `updater`.
  Each package contains a `*Placeholder.kt` documenting expected
  responsibilities for the v0.2+ implementation.
- README explaining architecture, branching, versioning, and roadmap.
- CHANGELOG.md (this file).
- Gradle Kotlin DSL build with version catalog (`gradle/libs.versions.toml`).

### Security
- `.gitignore` excludes `local.properties`, `secrets.properties`,
  keystore files, and `.env` files.
- No API tokens, keystore files, or passwords are committed to the
  repository.

### Known Limitations
- No agent functionality — chat, tools, memory, automation, and
  accessibility are all placeholders.
- The "Download & Install" button in the update dialog currently does
  not trigger the actual download (the repository method exists and is
  tested, but is not yet wired into the dialog button). This is
  scheduled for v0.1.1.
- Remote config `disableAgent` / `maintenanceMode` only render as status
  badges; they do not yet block the UI. Scheduled for v0.2.
- No unit tests yet — the architecture is testable but tests will be
  added alongside the v0.2 chat module.

### Tag
- Git tag `v0.1-alpha` is created on the `dev` branch.

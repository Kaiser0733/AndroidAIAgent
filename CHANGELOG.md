# Changelog

All notable changes to this project are documented in this file. The
format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/).

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

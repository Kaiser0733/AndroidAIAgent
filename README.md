# Android AI Agent

**Personal-use Android AI Agent — v0.1 foundation**

This repository is the long-term home of a personal Android AI Agent. The
v0.1 release is intentionally **foundation-only**: it ships a stable
architecture (application shell, update system, remote configuration,
logging) plus clearly-marked placeholders for the future agent modules
(chat, tools, memory, automation, accessibility). No AI functionality is
implemented yet — that arrives in v0.2 and beyond.

---

## Table of Contents
1. [Tech Stack](#tech-stack)
2. [Architecture](#architecture)
3. [Module Layout](#module-layout)
4. [Features in v0.1](#features-in-v01)
5. [Build & Run](#build--run)
6. [Versioning Strategy](#versioning-strategy)
7. [Branching Strategy](#branching-strategy)
8. [Roadmap](#roadmap)
9. [Security](#security)

---

## Tech Stack

| Layer            | Choice                                             |
|------------------|----------------------------------------------------|
| Language         | Kotlin 2.0                                         |
| UI               | Jetpack Compose + Material 3                       |
| Architecture     | MVVM (ViewModel + StateFlow) + Repository pattern  |
| DI               | Koin                                                |
| Async            | Kotlin Coroutines + Flow                           |
| Networking       | OkHttp                                              |
| Serialization    | kotlinx.serialization                               |
| Persistence      | DataStore Preferences                               |
| Logging          | Timber + custom file tree                          |
| Navigation       | Compose Navigation                                  |
| Build            | Gradle 8.7 Kotlin DSL + Version Catalog            |
| Min SDK          | 26 (Android 8.0)                                    |
| Target SDK       | 34 (Android 14)                                     |

---

## Architecture

The project follows a single-module MVVM + Repository architecture with
strict package-level separation. Each feature is split into three layers:

```
ui/screens/<feature>/         ← Compose UI + ViewModel (presentation)
data/<feature>/               ← Repository + DTOs (data)
future/<feature>/             ← Reserved for v0.2+ agent code (placeholders)
```

Data flows one way: **UI → ViewModel → Repository → (network / disk)**,
and state flows back up via `StateFlow`. Dependencies are wired by Koin
in `di/AppModule.kt`.

### Why MVVM + Repository?
- **ViewModel** survives configuration changes and exposes a single
  `StateFlow<UiState>` per screen, making the UI trivially testable.
- **Repository** is the only layer that touches the network or disk,
  so swapping OkHttp for another client (or adding a cache) does not
  require touching ViewModels or Composables.
- This layout scales cleanly when the agent modules arrive — each agent
  module will plug in as another repository + service without
  disturbing the shell.

---

## Module Layout

```
app/src/main/java/com/kaiser/aiagent/
├── AndroidAIAgentApp.kt          Application init (Timber, Koin, crash handler)
├── di/
│   └── AppModule.kt              Koin module wiring
├── data/
│   ├── logging/
│   │   ├── LogRepository.kt      File-backed log store (app/crash/update logs)
│   │   ├── FileLogger.kt         Timber tree that mirrors logs to file
│   │   └── CrashLogger.kt        Global uncaught-exception handler
│   ├── remote/
│   │   ├── RemoteConfig.kt       DTO for remote JSON config
│   │   └── RemoteConfigRepository.kt
│   └── updater/
│       ├── GitHubRelease.kt      DTO for GitHub Releases API
│       ├── UpdateCheckResult.kt  Sealed result type
│       ├── UpdateRepository.kt   Version check + APK download
│       └── UpdateStarter.kt      Hands off to the system installer
├── future/                       PLACEHOLDERS for v0.2+
│   ├── chat/
│   ├── tools/
│   ├── memory/
│   ├── automation/
│   ├── accessibility/
│   └── updater/
└── ui/
    ├── MainActivity.kt           Single-activity host
    ├── theme/                    Material 3 theme
    ├── navigation/
    │   └── Destinations.kt       Route constants
    └── screens/
        ├── launch/               Splash screen
        ├── home/                 Home + update check + future module list
        ├── settings/             Config URLs + auto-update toggle
        └── about/                Version info + description
```

---

## Features in v0.1

### 1. Application Shell
- Single-activity Compose host with Compose Navigation.
- **Launch screen** — branded splash, 1-second delay, then home.
- **Home screen** — agent status card, future-module cards, "Check for
  Updates" button, entry to Settings.
- **Settings screen** — edit remote config URL, updater repo, toggle
  auto-check for updates.
- **About screen** — version, application ID, repo link, license.

### 2. Update System
- Queries `https://api.github.com/repos/<owner>/<name>/releases/latest`.
- Parses `tag_name` and compares against installed `versionName` using a
  dotted-version comparator.
- Picks the APK asset best matching the device ABI (arm64-v8a,
  armeabi-v7a, x86_64, …) and falls back to a universal APK.
- Downloads the APK to `externalCacheDir/updates/` and invokes the
  system package installer via a `FileProvider` content URI.
- Shows an in-app "Update Available" dialog with release notes.
- All steps are logged to `logs/update.log`.

### 3. Remote Configuration
- Fetches a JSON document from a user-configurable URL.
- Schema (all fields optional):

```json
{
  "latestVersion": "0.2",
  "minimumVersion": "0.1",
  "disableAgent": false,
  "maintenanceMode": false,
  "maintenanceMessage": "Back soon."
}
```

- Last successful fetch is cached in DataStore so the app can boot
  offline using the previously known config.
- `disableAgent` and `maintenanceMode` are surfaced on the home screen
  as status badges (v0.2 will turn these into blocking screens).

### 4. Logging System
- `logs/app.log` — rolling application log (1 MB cap, tail-kept).
- `logs/crash.log` — last uncaught exception stack trace.
- `logs/update.log` — updater activity (each check / download / install
  attempt is recorded).
- All `Timber.x(...)` calls mirror to file automatically.
- A global `Thread.setDefaultUncaughtExceptionHandler` writes crashes
  before the process dies.

### 5. Architecture Preparation
Six placeholder packages under `future/` mark where the real agent
modules will land. Each contains a `*Placeholder.kt` file with a
document explaining the expected responsibilities. No agent code is
compiled in v0.1.

---

## Build & Run

### Prerequisites
- Android Studio Hedgehog (or newer) **or** command-line Gradle.
- JDK 17+ (the project targets JVM 17 bytecode).
- Android SDK with platform `android-34` and `build-tools;34.0.0`.

### Build a debug APK
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Install on a connected device
```bash
./gradlew installDebug
```

### Run unit tests
```bash
./gradlew test
```

---

## Versioning Strategy

This project follows **Semantic Versioning** with a `-alpha` / `-beta`
suffix during the foundation phase:

| Version        | Meaning                                                  |
|----------------|----------------------------------------------------------|
| `0.1`          | Application code version (`BuildConfig.VERSION_NAME`)    |
| `v0.1-alpha`   | Git tag for the first foundation drop                    |
| `v0.2-alpha`   | First agent functionality (chat prototype)               |
| `0.1.0`        | First *release candidate* (no suffix)                    |

Rules:
- `versionCode` (int) is monotonically increasing per published build.
- `versionName` (string) follows `MAJOR.MINOR.PATCH` semantics.
- Git tags are prefixed with `v` (e.g. `v0.1-alpha`).
- Pre-release tags use `-alpha` (early), `-beta` (feature-complete but
  unpolished), `-rc.N` (release candidate N).

---

## Branching Strategy

| Branch | Purpose                                   |
|--------|-------------------------------------------|
| `main` | Stable production. Always builds, always green. |
| `dev`  | Active development. Feature branches merge here first. |

- All work happens on `dev` (or short-lived feature branches off `dev`).
- `main` is only fast-forwarded from `dev` when a milestone is reached.
- Tags are created on `dev` for alpha/beta releases, and on `main` for
  stable releases.

---

## Roadmap

| Version | Theme                                                   |
|---------|---------------------------------------------------------|
| 0.1     | Foundation (this release)                              |
| 0.2     | Chat module — GLM integration, multi-turn conversation |
| 0.3     | Tools module — first pluggable tools                   |
| 0.4     | Memory module — persistent facts + preferences         |
| 0.5     | Accessibility module — UI tree reading                 |
| 0.6     | Automation module — UI action pipelines                |
| 0.7     | Agent planning + orchestration                         |
| 1.0     | First stable release                                   |

---

## Security

- **No secrets are committed to the repository.** API keys, tokens, and
  keystore files are kept in `local.properties` / `secrets.properties`,
  both of which are git-ignored.
- The updater only fetches from `https://api.github.com` and from the
  GitHub asset download URLs served over HTTPS.
- The system package installer always prompts the user — silent install
  is impossible without root.
- Any personal access tokens used for repository push must be supplied
  via environment variables only and rotated immediately after use.

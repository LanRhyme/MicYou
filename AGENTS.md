# Repository Guidelines

## Project Overview

MicYou turns Android devices into PC microphones. The Android client captures microphone audio and streams it over the network; the desktop app receives it and plays it back through a virtual mic device (VB-CABLE on Windows, BlackHole on macOS, PipeWire on Linux) or a browser via HTTPS. Two app surfaces share one wire protocol: a single-module Android app (`:composeApp`, Kotlin + Jetpack Compose + Material 3) and a desktop app (`tauri-app/`, Tauri 2 + Rust backend + Vue 3/Vite/Tailwind frontend). The desktop Rust core is reused by three frontends — GUI (`micyou`), CLI (`micyou-cli`), and TUI (`micyou-tui`) — which share the same server lifecycle, config files, and DSP settings.

## Architecture & Data Flow

```mermaid
flowchart LR
  P[Android :composeApp] -- "UDP audio (port+1, 'MicU')" --> U[udp_server]
  P -- "TCP control (8554, 'MicY')" --> T[tcp_server]
  U --> J[JitterBuffer + FEC]
  J --> D[DSP chain AEC→NR→Dereverb→EQ→AGC→VAD]
  D --> O[cpal output / virtual mic / WebSocket]
  G[Vue GUI] -- "invoke / listen" --> C[micyou-core: server::start_server]
  L[micyou-cli] -- serve subcommand --> C
  Y[micyou-tui] -- serve subcommand --> C
  C --> U & T
```

- **Android**: single Activity (`MainActivity`) hosting a Compose tree (`App.kt` → `MobileHome`); no Fragments/Navigation. MVVM: `AudioStreamViewModel` (owns `AudioEngine` + mDNS discovery), `SettingsViewModel`, and `UpdateViewModel` are merged by a facade `MainViewModel` via `combine()` into one `AppUiState` StateFlow. `AudioEngine` captures via `AudioRecord`, applies a Kotlin DSP chain, and sends protobuf packets over TCP (control: connect/mute/ping/pong) + UDP (audio, port = TCP+1, FEC every 12 packets, magics `0x4D696359`/`0x4D696355`). A foreground `AudioService` keeps streaming alive; a Quick Settings tile starts/stops it.
- **Desktop backend** (`tauri-app/crates/micyou-core` + `src-tauri` shell): all server logic lives in `micyou-core`, which has no Tauri dependency; `src-tauri` is a thin adapter (commands, tray, windows, `TauriEventSink`, `TauriHost`). The wire protocol is compiled by `micyou-protocol` (prost from `proto/network.proto`). `transport::udp` validates/parses audio datagrams into an `mpsc(128)` channel; a dedicated audio thread (`server::audio_pipeline`) reorders + FEC-recovers (`transport::jitter_buffer`), decodes PCM (16/8/float/24-bit), runs the `micyou-audio` DSP chain (AEC pinned first, ONNX/RNNoise noise suppression), and plays via cpal. All events fan out through the `ServerEvents` trait — `TauriEventSink` (webview events), `CliEventSink` (log lines), `TuiEventSink` (throttled mpsc) ; OS integration the core needs (open URL, notify, global hotkeys, plugin panel windows) goes through the `HostIntegration` trait — `TauriHost` for the GUI, `HeadlessHost` (feature `headless-host`) for CLI/TUI. Every frontend builds `ServerState::new(events, host, resource_hint)` and calls `server::start_server`/`stop_server`; runtime controls (mute, monitoring, DSP settings) go through `ServerState::controls()`, which the plugin host API shares. Three connection modes: **wifi** (LAN, mDNS `_micyou._tcp.`), **usb** (`adb reverse`), **web** (axum TLS WebSocket, feature-gated). GUI/CLI/TUI are mutually exclusive via a `mode.lock` file.
- **Shared config**: `~/.config/micyou/` (Linux) / `%APPDATA%\micyou` (Windows): `settings.json` (DSP), `server.json` (port 8554, webPort 8443, mode, bindAddress, outputDevice), `ui.json` (language/theme), `theme.json` (theme colors exported GUI → CLI/TUI). All three frontends read/write the same files.
- **Version flow**: root `gradle.properties` (`project.version`, `project.version.code`) is the single source of truth; `bun run sync-version` rewrites `tauri.conf.json`, `src-tauri/Cargo.toml`, and `package.json` (it does NOT touch the workspace root `Cargo.toml` — a known drift risk). It also runs automatically via `beforeBuildCommand` on every `tauri build`.

## Key Directories

| Path | Purpose |
|---|---|
| `composeApp/` | Android client module (`com.lanrhyme.micyou`); the only Gradle module |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/audio/` | `AudioEngine`, audio settings/source/metrics |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/network/` | Protobuf wire protocol, mDNS discovery, connection error mapping |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/{service,viewmodel,ui,settings,theme,util,update}` | Foreground service, ViewModels, Compose UI, prefs, theming, localization, update flow |
| `tauri-app/src/` | Vue 3 frontend: `platform/` (the only code that imports `@tauri-apps/*`: typed `command()`, `onEvent()`/`emitToWindow()`, `appWindow`, shell helpers, backend DTOs), `features/` (connection, audio, theme, pocket, settings, plugins, onboarding, window, tray), `shared/` (`lib/os.ts` platform detection, `lib/utils.ts`, components/ui incl. `MD3Switch`, locales, assets), `i18n.ts` (locale registration and switching) |
| `tauri-app/crates/micyou-core/` | Tauri-free server core: `server/` (state, lifecycle, service, audio pipeline, output), `transport/` (tcp/udp/web, jitter buffer, opus, net_bind), `plugins/`, `platform/` (adb, pipewire, vbcable, blackhole, accent, firewall, terminal), `config`, `settings`, `events`, `host`, `modes` |
| `tauri-app/src-tauri/` | Tauri 2 GUI shell: `app.rs` (builder/setup), thin `commands/`, `events.rs`, `host.rs`, `tray.rs`, `window.rs` (window commands, settings window, rounded corners), `kwin_effects.rs` (Linux: KWin blur + shadow on the GDK Wayland surface) |
| `tauri-app/crates/micyou-protocol/` | protobuf wire format + magic constants (shared with Android `network/Protocol.kt`) |
| `tauri-app/crates/micyou-audio/` | cpal output engine, DSP chain (ONNX/RNNoise), loopback capture |
| `tauri-app/crates/micyou-plugin/` | Plugin runtime framework (manifest, WASM sandboxing via wasmi, Native C ABI, bus, DSP hook) |
| `tauri-app/crates/micyou-cli/`, `micyou-tui/` | CLI and TUI frontends on top of `micyou-core` (no Tauri in their dependency tree) |
| `plugins/` | Example plugins (`native-soundpad`, `wasm-voicechanger`, `wasm-audioinspector`) |
| `docs/` | FAQ stubs redirecting to micyou.top, plugin specifications, and Android compatibility guide |
| `.github/workflows/` | CI: development, release, pre-release, MirrorChyan uploads, opencode AI review |

## Development Commands

```bash
# Android debug/release APK (from repo root)
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:assembleRelease

# Desktop frontend (from tauri-app/)
bun run dev            # Vite dev server, port 1420 (strict)
bun run build          # vue-tsc --noEmit && vite build — the only static type gate
bun run preview

# Tauri desktop app (GUI)
bun run tauri dev
bun run tauri build    # runs sync-version + bun run build first (beforeBuildCommand)

# Alternate frontends (Rust workspace, from tauri-app/)
cargo run -p micyou-cli -- serve            # CLI server (binary: micyou-cli)
micyou-cli settings get/set, chain list/set # CLI subcommands (clap)
cargo run -p micyou-tui                 # TUI frontend

# Version bump flow
# 1. edit gradle.properties (project.version / project.version.code)
# 2. bun run sync-version                # propagates to tauri.conf.json / Cargo.toml / package.json
```

There are **no** lint, format, or test scripts anywhere (no eslint/prettier/ktlint wiring).

## Code Conventions & Common Patterns

- **Localization**: user-facing strings never hardcoded. Android: `composeApp/src/main/res/values*/strings.xml` (base `values/` = English; also en, zh, zh-rTW, zh-rHK, plus easter eggs zh-rHD "hard mode", ca "cat speak"), languages registered in `util/Localization.kt` (`AppLanguage` enum). Desktop: `tauri-app/src/shared/locales/*.json` (en base, zh, zh-hk, zh-tw, zh-ss, cat, lzh), registered in `src/i18n.ts`. **Adding/renaming a key requires updating every locale file.**
- **Android**: MVVM with a single `AppUiState` facade collected by UI; settings stored in SharedPreferences (`"android_mic_prefs"`), **not** DataStore; settings-as-enums (`AudioSettings.kt`); wire constants centralized in `util/Constants.kt` + `network/Protocol.kt`; state-driven dialogs instead of navigation.
- **Rust backend**: Tauri commands are `snake_case` in `invoke_handler`; long-lived state is `Arc`-wrapped inside `ServerState` (managed Tauri state); lifecycle serialized via `ServerLifecycleGate` + `CancellationToken`; audio/DSP settings use `serde` with `camelCase` field names; unit tests inline as `#[cfg(test)]` modules.
- **Vue frontend**: no Pinia — state lives in singleton composables (`useServer`, `useAudio`, `useTheme`, `useWindow`, `useTray`) instantiated in `App.vue` and passed via props/events; UI-only prefs persist via `@vueuse/core useStorage` with `micyou_*` localStorage keys, while anything the CLI/TUI also reads lives in the shared config files. Backend access goes through `src/platform/` only: `command('snake_case_cmd', args)` is typed by the `Commands` map in `platform/commands.ts` and `onEvent('event-name', payload => …)` by `AppEvents` in `platform/events.ts`; a new command or event must be added there first. Never import `@tauri-apps/*` outside `src/platform/`. Every invoke is try/catch-wrapped with optimistic updates + rollback; connection errors funnel into `ConnectionErrorDialog` via `utils/connectionError.ts`. Windows are frameless and transparent with self-drawn chrome; mark glass panels with `data-blur-region` so `useWindowEffects` can request matching native blur. The settings window is a separate webview (`features/settings/components/SettingsWindow.vue` shell + one component per page under `sections/`, state in `features/settings/composables/`): it shares state with the main window through `useStorage` keys (storage events) and `emitToWindow('main', …)`; it drags from any element marked `data-drag-surface` that is not a control. Use `MD3Switch` for toggles. Dialogs rendered inside a section use `<Teleport to="body">` so `fixed` positioning escapes the page transition.
- **Styling**: Tailwind 4 utilities + Material 3 HSL CSS variables (8 themes × light/dark via `.dark` class); there is no `tailwind.config.js` or PostCSS config: the theme lives in the `@theme inline` block of `src/shared/assets/index.css` (loaded through `@tailwindcss/vite`, animations from `tw-animate-css`), and a scoped `<style>` that uses `@apply` needs `@reference "@/shared/assets/index.css";`; feature components use raw Tailwind, `src/shared/components/ui/*` holds only what is used (reka-ui `select`, `MD3Slider`, `MD3Switch`); the shadcn-only color names (`popover`, `accent`, `muted`, …) are aliased to Material 3 tokens in `src/shared/assets/index.css`; `cn() = twMerge(clsx(...))` from `@/shared/lib/utils`.
- **Versioning**: bump only `gradle.properties`; never hand-edit `tauri.conf.json`/`Cargo.toml`/`package.json` versions.
- **Communication**: ALWAYS use Chinese (中文) for code reviews, issue comments, pull request comments, and any other user-facing communication.

## Important Files

| File | Role |
|---|---|
| `gradle.properties` | Version source of truth (`project.version`, `project.version.code`) |
| `gradle/libs.versions.toml` | All Android dependency/SDK versions (AGP, Kotlin, compileSdk 37, minSdk 24, targetSdk 36); the `-Pmicyou.androidCompat=api21` downgrades live in `settings.gradle.kts` |
| `composeApp/build.gradle.kts` | Android module config; release signing gated on `ANDROID_KEYSTORE_*` env vars |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/MainActivity.kt`, `App.kt` | Android entry points |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/audio/AudioEngine.kt` | Core streaming engine (capture → DSP → TCP/UDP transport) |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/viewmodel/MainViewModel.kt` | UI state facade; app-wide state enums |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/network/Protocol.kt` | Wire protocol (must stay in sync with `micyou-protocol`) |
| `composeApp/src/main/kotlin/com/lanrhyme/micyou/util/Localization.kt` | `AppLanguage` enum; locale switching |
| `tauri-app/package.json` | bun scripts (dev/build/tauri/sync-version); version synced from gradle.properties; `packageManager` pins the bun version CI installs |
| `tauri-app/sync-version.js` | Version propagation script (also `beforeBuildCommand`) |
| `tauri-app/src-tauri/tauri.conf.json` | Tauri app config (window, bundle targets, beforeBuildCommand); `tauri.linux.conf.json` pins the main window with min = max size because GTK3 grows non-resizable windows by 48px on Wayland |
| `tauri-app/src-tauri/src/app.rs` | GUI entry; plugins, setup and every command in `invoke_handler` |
| `tauri-app/crates/micyou-core/src/server/service.rs` | `start_server`/`stop_server`, `StartRequest` — shared server lifecycle |
| `tauri-app/crates/micyou-core/src/config.rs` | Shared config load/save (`settings.json`, `server.json`, `ui.json`, `theme.json`) |
| `tauri-app/crates/micyou-core/src/events.rs`, `host/mod.rs` | `ServerEvents` and `HostIntegration` traits every frontend implements |
| `tauri-app/src/main.ts` | Frontend entry; hash-based multi-window routing (`#/settings` → `SettingsWindow`, `#/plugin/` → `PluginPanelWindow`, else `App`) |
| `tauri-app/src/App.vue` | Main window (full + pocket modes, self-drawn titlebar), wires all composables; opens settings via `open_settings_window` |
| `tauri-app/crates/micyou-protocol/proto/network.proto` | Wire format source (prost-compiled) |
| `tauri-app/crates/micyou-audio/src/dsp.rs` | DSP settings struct + `DspProcessor` |

## Runtime/Tooling Preferences

- **Android**: JDK 21 (Java 11 bytecode target), Gradle 9.8.0 wrapper, AGP 9.4.1, Kotlin 2.4.20, compileSdk 37, targetSdk 36, minSdk 24, build-tools 36.1.0. Optional build-time config in `local.properties`: `AIFADIAN_API_TOKEN`, `AIFADIAN_USER_ID`.
- **Desktop**: Bun (`bun.lock` committed; CI uses `oven-sh/setup-bun` + `bun install --frozen-lockfile`; helper scripts run under bun; `generate-licenses.js` reads the production dependency tree from `bun.lock` and reruns cargo-about only when its inputs change, `bun generate-licenses.js --force` rebuilds); Rust stable (edition 2021) via cargo; Tauri CLI 2 (`bun run tauri`); Vite dev server fixed at port 1420 with `TAURI_DEV_HOST` for HMR.
- **Release signing**: all four of `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` required, else release builds are unsigned. CI uses `ANDROID_KEYSTORE_BASE64`.
- **VS Code**: extensions.json recommends Volar, tauri-vscode, rust-analyzer. `.prettierrc` exists (2-space, singleQuote, printWidth 100) but no formatter is wired into scripts.
- **Known oddities**: Compose code reaches Haze only through `ui/compose/haze/HazeBridge.kt`, which exists twice: `src/normal` maps the bridge's own `HazeStyle`/`HazeTint` onto Haze 2 `hazeBlur` (`haze-blur` artifact, normal builds only), and `src/compat` draws a plain background; desktop code lists, saves and matches audio devices through `micyou_audio::device_name`, never `cpal::Device::description()` directly (on ALSA it keeps the PCM name stored in `server.json`); the `api21` compat build applies kotlin-android through `apply()`, so `composeApp/build.gradle.kts` configures `KotlinAndroidProjectExtension` by type instead of a `kotlin {}` block, and every library that needs compileSdk 37 must be pinned back in `settings.gradle.kts` (filekit stays on 0.13.0 there); `composeApp/micyou.conf` is a gitignored leftover with zero code references; `docs/FAQ*.md` are redirect stubs (content lives at micyou.top).

## Testing & QA

- **Tests are minimal by design.** Evidence: no `src/test`/`src/androidTest` in `composeApp` (kotlin-test in the version catalog is unused); no integration test dirs in the Rust workspace. Rust unit tests are inline `#[cfg(test)]` modules, mostly in `micyou-core` (server, transport, plugins, platform, modes), plus `micyou-audio`, `micyou-plugin` and the GUI tray; `micyou-protocol` and `micyou-cli` have none.
- **Run Rust tests**: `cargo test` from `tauri-app/` (workspace).
- **Static checks**: the only automated gate is `vue-tsc --noEmit` inside `bun run build`. There is no lint/format automation.
- **CI** (`.github/workflows/`): `development.yml` builds the debug APK + Tauri packages on Windows/macOS/Linux for push/PR; `release.yml`/`pre-release.yml` build release artifacts and publish GitHub/MirrorChyan releases. Android CI steps run with `continue-on-error: true` and releases do not depend on the Android job — Android failures never block releases. `opencode.yml` runs an AI code review (Chinese prompt) on PR comments.
- **QA expectation**: manual end-to-end verification of the audio path (phone → server → virtual mic) is the de facto pipeline; keep changes build-green (`assembleDebug` + `bun run build`) and preserve `#[cfg(test)]` conventions for new Rust logic.

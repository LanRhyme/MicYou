# AGENTS.md — Tauri App

Tauri 2 desktop app with Vue 3 + TypeScript frontend and Rust backend. This is the newer desktop client for MicYou, separate from the Kotlin Multiplatform app at the repo root.

## Architecture

### Frontend (Vue 3 + TypeScript + Tailwind CSS)

Feature-driven structure — each feature owns its `components/` and `composables/`:

```
src/
  features/
    audio/          # Audio visualization, monitoring, mute
    connection/     # Server, network, USB, QR code
    onboarding/     # First-run wizard
    pocket/         # Compact pocket mode layout
    settings/       # Settings dialog
    theme/          # Material You theme engine
  shared/
    components/     # Reusable UI (PopupWindow, CustomBackground, dialogs)
    composables/    # Shared composables (useWindow, useTray)
    lib/            # Utility libraries
    locales/        # i18n JSON files (en.json, zh.json)
    assets/         # CSS, SVGs, static assets
  App.vue           # Root component
  main.ts           # Entry point (also handles popup windows via hash routing)
```

- Vue `<script setup>` SFCs, Composition API throughout
- i18n via `vue-i18n`; add strings to both `src/shared/locales/en.json` and `zh.json`
- Path alias: `@/` maps to `src/` (configured in `vite.config.ts` and `tsconfig.json`)

### Backend (Rust, Cargo workspace)

```
src-tauri/              # Main Tauri app crate (micyou-app)
  src/
    commands/           # Tauri invoke commands
    lib.rs              # Tauri plugin registration
    main.rs             # Entry point
    tcp_server.rs       # TCP audio server
    udp_server.rs       # UDP audio server
    web_server.rs       # HTTPS web server (feature-gated)
    adb_manager.rs      # ADB device management (feature-gated)
    vbcable.rs          # VB-Cable installer (feature-gated)
    ...
  proto/                # (empty; protocol proto is in micyou-protocol)
crates/
  micyou-protocol/      # Protobuf protocol definitions + codegen
    proto/network.proto # Protocol definition
    build.rs            # prost codegen
  micyou-audio/         # Audio DSP crate
```

**Cargo features** (in `src-tauri/Cargo.toml`):
- `vbcable` (default) — Windows VB-Cable installer; pulls in `zip`
- `adb` (default) — ADB device management
- `web-server` (default) — HTTPS web server for browser-based mic; pulls in `axum`, `rustls`

## Commands

```bash
npm install                  # Install frontend deps
npm run dev                  # Vite dev server only (port 1420)
npm run build                # vue-tsc --noEmit && vite build
npm run tauri dev            # Full Tauri dev (frontend + Rust hot-reload)
npm run tauri build          # Production build
npm run sync-version         # Sync version from ../gradle.properties → tauri.conf.json, Cargo.toml, package.json

# Rust workspace
cargo build                  # Build all crates
cargo test                   # Run tests
cargo build -p micyou-protocol   # Build protocol crate only
cargo build -p micyou-audio      # Build audio crate only
```

## Version management

Version is synced from `../gradle.properties` (`project.version`) via `node sync-version.js`. This runs automatically before `npm run build`. It updates:
- `src-tauri/tauri.conf.json`
- `src-tauri/Cargo.toml` (workspace version in root `Cargo.toml`)
- `package.json`

Do not edit version numbers in Tauri/Cargo/package.json directly — always change `gradle.properties` first.

## Environment requirements

- Node.js + npm
- Rust toolchain (stable)
- Platform-specific Tauri prerequisites (see [Tauri docs](https://v2.tauri.app/start/prerequisites/))
- App identifier: `com.lanrhyme.micyou`

## Key conventions

- Frontend strings use `vue-i18n` (`t('key')`) with JSON locale files, not hardcoded
- Keep features self-contained: components + composables inside each feature directory
- Shared/reusable code goes in `src/shared/`
- Rust code uses `prost` for protobuf codegen; `.proto` files live in `crates/micyou-protocol/proto/`
- Tauri commands are registered in `src-tauri/src/lib.rs`
- Popup windows use hash-based routing in `main.ts` (`#/popup/ip`, `#/popup/*`)

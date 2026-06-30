# AGENTS.md

## Project overview

MicYou turns Android devices into PC microphones.

This checkout contains two app surfaces:

- Android client: Kotlin + Jetpack Compose + Material 3 in `:composeApp`
- Desktop app: Tauri 2 + Rust backend + Vue 3/Vite/Tailwind frontend in `tauri-app`

Shared protocol/audio code for the desktop side lives in Rust crates under `tauri-app/crates`.

## Module structure

- `:composeApp` - Android application module.
  - Main activity: `composeApp/src/main/kotlin/com/lanrhyme/micyou/MainActivity.kt`
  - Android services: `composeApp/src/main/kotlin/com/lanrhyme/micyou/service/`
  - Audio, network, settings, plugin host, and Compose UI code live under `composeApp/src/main/kotlin/com/lanrhyme/micyou/`
- `:plugin-api` - Referenced by `settings.gradle.kts` and `composeApp`, but the `plugin-api/` directory is not present in this checkout. Restore it before expecting a full Gradle sync/build to succeed.
- `tauri-app` - Desktop application.
  - Vue frontend: `tauri-app/src/`
  - Rust/Tauri backend: `tauri-app/src-tauri/src/`
  - Rust workspace crates: `tauri-app/crates/micyou-protocol` and `tauri-app/crates/micyou-audio`
- `docs` - Project documentation.
- `img` - README and project images.

## Build commands

```bash
# Android debug APK
./gradlew :composeApp:assembleDebug

# Desktop frontend checks/build
cd tauri-app
npm run build

# Desktop Tauri development/build
cd tauri-app
npm run tauri dev
npm run tauri build

# Sync Tauri/package versions from gradle.properties
cd tauri-app
npm run sync-version
```

Use `npm install` in `tauri-app` only when dependencies need to be restored or updated. `tauri-app/node_modules` is present in this workspace.

## Localization

- Android strings use standard Android resources in `composeApp/src/main/res/values*/strings.xml`.
- Android language choices are registered in `AppLanguage` in `composeApp/src/main/kotlin/com/lanrhyme/micyou/util/Localization.kt`.
- Current Android resource locales include base `values`, `values-en`, `values-zh`, `values-zh-rTW`, `values-zh-rHK`, `values-zh-rHD`, and `values-ca`.
- Desktop strings use Vue I18n JSON files in `tauri-app/src/shared/locales/`.
- Keep localization keys aligned across locale files when adding or renaming user-facing text.

## Environment requirements

- Android SDK: compileSdk 36, minSdk 24, targetSdk 36.
- Kotlin/Android versions are defined in `gradle/libs.versions.toml`.
- Version is set in `gradle.properties` with `project.version` and `project.version.code`.
- Optional Android sponsorship config comes from `local.properties`: `AIFADIAN_API_TOKEN` and `AIFADIAN_USER_ID`.
- Optional Android release signing uses `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
- Desktop development needs Node/npm plus Rust/Cargo and the platform requirements for Tauri 2.

## Key conventions

- Keep user-facing strings in resource/i18n files instead of hardcoding them.
- Android package name: `com.lanrhyme.micyou`.
- Tauri package/app version should stay in sync with `gradle.properties`; use `tauri-app/sync-version.js` via `npm run sync-version`.
- Plugin API version is in `gradle.properties` as `pluginApiVersion`.
- Prefer existing feature folders and shared components:
  - Android: `audio`, `network`, `settings`, `ui`, `viewmodel`, `plugin`, `util`
  - Desktop: `tauri-app/src/features`, `tauri-app/src/shared`, and `tauri-app/src-tauri/src/commands`

# AGENTS.md

## Project overview

MicYou is a Kotlin Multiplatform app (Android + Desktop) that turns Android devices into PC microphones.
Built with Compose Multiplatform, Material 3, Ktor, and kotlinx-serialization.

## Module structure

- `:composeApp` - Main application (Android + Desktop JVM). Entry points:
  - Desktop: `com.lanrhyme.micyou.MainKt` (`composeApp/src/jvmMain/kotlin/com/lanrhyme/micyou/main.kt`)
  - Android: `MainActivity.kt` (`composeApp/src/androidMain/kotlin/com/lanrhyme/micyou/MainActivity.kt`)
- `:plugin-api` - Plugin API library (`plugin-api/src/commonMain/kotlin/com/lanrhyme/micyou/plugin/`)
- `exampleplugins/sample-plugin` - Example plugin (standalone Gradle project, not included in main build)
- `buildSrc` - Contains `CheckLocalizationTask` for localization validation

## Source sets (Kotlin Multiplatform)

- `commonMain` - Shared UI and logic
- `androidMain` - Android-specific (AudioService, permissions, TileService)
- `jvmMain` - Desktop-specific (audio processing, plugin loading, virtual audio device, system tray)

## Build commands

```bash
# Run desktop app
./gradlew :composeApp:run

# Build Android debug APK
./gradlew :composeApp:assembleDebug

# Distribution packages (desktop)
./gradlew :composeApp:packageWindowsNsis   # Windows installer (requires NSIS)
./gradlew :composeApp:packageWindowsZip    # Windows ZIP
./gradlew :composeApp:packageDeb           # Linux DEB
./gradlew :composeApp:packageRpm           # Linux RPM
./gradlew :composeApp:packageDmg           # macOS DMG

# No-JRE packages (user must have Java installed)
./gradlew :composeApp:packageNoJreAll
```

## Localization

- Uses Compose Multiplatform Resources (`composeApp/src/commonMain/composeResources/values*/strings.xml`)
- Base languages: English (`values/strings.xml`) and Simplified Chinese (`values-zh/strings.xml`)
- Adding a new language: create `values-xx/strings.xml`, register in `Localization.kt` (`AppLanguage` enum), codes using ISO 639-1 or IETF BCP 47 standards are recommended
- Validation: `./gradlew checkLocalization` (blocks commits via pre-commit hook)
- Install hooks: `./gradlew installGitHooks`
- PRs should be submitted directly to update translation files, updating both base languages first

## Environment requirements

- JDK 21 (Liberica distribution used in CI; other OpenJDK distributions is accepted)
- Android SDK: compileSdk 36, minSdk 24, targetSdk 36
- Version is set in `gradle.properties` (`project.version` and `project.version.code`)
- Optional: `local.properties` for `AIFADIAN_API_TOKEN` / `AIFADIAN_USER_ID` (sponsorship features)
- Optional: `ANDROID_KEYSTORE_*` env vars for release signing

## Pre-commit hook

Runs `./gradlew -q checkLocalization` automatically. Install with `./gradlew installGitHooks`.

## Key conventions

- All user-facing strings go through Compose Resources (`Res.string.*`), not hardcoded
- Localization keys must be consistent across all `values*/strings.xml` files
- Package name: `com.lanrhyme.micyou`
- Desktop main class: `com.lanrhyme.micyou.MainKt`
- Plugin API version is in `gradle.properties` (`pluginApiVersion`)

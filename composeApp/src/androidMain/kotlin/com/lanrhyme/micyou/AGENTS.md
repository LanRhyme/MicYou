# Android Platform Implementation

## OVERVIEW
Android-specific implementations: MainActivity, AudioService foreground service, AudioEngine.android.kt, Android plugin system, Platform.android.kt.

## WHERE TO LOOK
| File | Role |
|------|------|
| MainActivity.kt | Entry point, permission handling, quick start intent |
| AudioService.kt | Foreground service for microphone pipeline |
| AudioEngine.android.kt | AudioEngine actual implementation (expect in commonMain) |
| Platform.android.kt | Platform actual: getPlatform(), Logger, dynamic colors |
| AndroidLogger.kt | LoggerImpl for Android logging |
| plugin/AndroidPluginManager.kt | Plugin loading for Android |
| plugin/AndroidPluginHostImpl.kt | PluginHost implementation |
| AudioSource.kt | Android audio source options |

## CONVENTIONS
- Use AndroidContext for Context access across the app
- Foreground service requires notification channel setup
- Permission handling: RECORD_AUDIO, POST_NOTIFICATIONS (Tiramisu+)
- Settings via SharedPreferences (Settings.android.kt)

## ANTI-PATTERNS
- Do not block main thread from AudioService
- Avoid leaking Activity context in static fields
- Never bypass runtime permission checks
- Do not touch UI from non-main threads
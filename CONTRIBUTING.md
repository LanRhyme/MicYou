# Contributing to MicYou

First of all, thank you for your interest in contributing to MicYou! We welcome all kinds of contributions, whether it's bug reports, feature requests, code contributions, or translations.

## Building from Source

This project is built using Kotlin Multiplatform.

**Android app (APK):**
```bash
./gradlew :composeApp:assembleDebug
```

**Desktop application (run directly):**
```bash
./gradlew :composeApp:run
```

**Build packages for distribution:**

**Windows installer (NSIS):**
```bash
./gradlew :composeApp:packageWindowsNsis
```

**Windows ZIP archive:**
```bash
./gradlew :composeApp:packageWindowsZip
```

**Linux DEB package:**
```bash
./gradlew :composeApp:packageDeb
```

**Linux RPM package:**
```bash
./gradlew :composeApp:packageRpm
```

## Internationalization (i18n)

MicYou uses Compose Multiplatform Resources for localization. All user-facing strings are stored in `strings.xml` files. We welcome contributions to translate MicYou into your language!

### Translation via Crowdin (Recommended)

The easiest way to contribute translations is through [Crowdin](https://crowdin.com/project/micyou). No local development setup needed:

1. Visit [MicYou on Crowdin](https://crowdin.com/project/micyou)
2. Sign up or log in with your GitHub account
3. Select your language from the list
4. Translate strings directly in the web interface
5. Submit translations for review

When translations are merged, they are automatically synchronized to the repository via GitHub Actions.

### Adding a New Language (Manual)

To add a new language manually:

1. Clone the repository:
```bash
git clone https://github.com/LanRhyme/MicYou.git
cd MicYou
```

2. Create a new `strings.xml` file under the appropriate `values-{locale}` directory:
```bash
mkdir -p composeApp/src/commonMain/composeResources/values-xx
cp composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-xx/strings.xml
```
Replace `xx` with your language code (e.g., `fr` for French, `es` for Spanish).

3. Edit the new `strings.xml` file and translate all string values while keeping the keys unchanged:
```xml
<resources>
    <string name="appName">MicYou</string>
    <string name="ipLabel">IP : </string>
    <!-- ... -->
</resources>
```

4. Register the new language in [Localization.kt](composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Localization.kt):

Find the `AppLanguage` enum and add your language:
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... existing languages ...
    French("Français", "fr"),  // Add this line
}
```

### Using Strings in Code

```kotlin
// In @Composable context
Text(stringResource(Res.string.myKey))

// In suspend context
val text = getString(Res.string.myKey)

// With format args (%s, %d, %1$s for positional)
Text(stringResource(Res.string.myFormattedKey, arg1))
```

### Testing Translations

To test your translation locally:

1. Build and run the desktop app:
```bash
./gradlew :composeApp:run
```

2. Go to **Settings → Appearance → Language** and select your new language

3. Verify all strings are properly translated and layouts look correct

4. For Android app, build APK:
```bash
./gradlew :composeApp:assembleDebug
```

### Translation Workflow

- **Base languages (must be kept in sync)**: English (`values/strings.xml`) and Simplified Chinese (`values-zh/strings.xml`)
- **Location**: `composeApp/src/commonMain/composeResources/values*/strings.xml`
- **File format**: Android strings.xml
- **Currently supported**: 6 locales including Chinese (Simplified, Traditional, Cantonese)

### Translation Update Process (GitHub workflow)

When you add or update translations, follow this order:

1. Update both base language files first:
```
composeApp/src/commonMain/composeResources/values/strings.xml      (English)
composeApp/src/commonMain/composeResources/values-zh/strings.xml   (Simplified Chinese)
```

2. Update other locale files (`values-*/strings.xml`) using the same key set.

3. Run localization checks locally:
```bash
./gradlew checkLocalization
```

4. Install hooks once (recommended) so checks run automatically before each commit:
```bash
./gradlew installGitHooks
```

5. Commit changes only after `checkLocalization` passes.

The pre-commit hook runs `checkLocalization` and blocks commits when key sets are inconsistent or values are empty.

### Special Language Variants

Some languages have special variants:
- `values-zh/` - Simplified Chinese
- `values-zh-rTW/` - Traditional Chinese (Taiwan)
- `values-zh-rHK/` - Cantonese (Hong Kong)
- `values-zh-rSS/` - Chinese Hard mode (Easter egg)
- `values-ca/` - Cat language (Easter egg)

### Contributing Translations

1. **Via Crowdin** (Recommended): Join our Crowdin project for collaborative translation
2. **Via GitHub**: Submit a pull request with your new/updated translation files
3. Include the language name in English and native language in your PR title eg: Add xx(code) localization

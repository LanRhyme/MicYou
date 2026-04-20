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

MicYou supports multiple languages with a robust translation system. We welcome contributions to translate MicYou into your language!

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

2. Copy the English translation file as a template:
```bash
cp composeApp/src/commonMain/composeResources/files/i18n/strings_en.json composeApp/src/commonMain/composeResources/files/i18n/strings_xx.json
```
Replace `xx` with your language code (e.g., `fr` for French, `es` for Spanish).

3. Edit the new JSON file and translate all string values while keeping the keys unchanged:
```json
{
  "appName": "MicYou",
  "ipLabel": "IP: ",
  ...
}
```

4. Register the new language in [Localization.kt](composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Localization.kt):

Find the `AppLanguage` enum and add your language:
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... existing languages ...
    French("Français", "fr"),  // Add this line
}
```

Also update the `getStrings()` function to handle your language:
```kotlin
fun getStrings(language: AppLanguage): AppStrings {
    val langCode = when (language) {
        // ... existing cases ...
        AppLanguage.French -> "fr"
        // ...
    }
    // ...
}
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

- **Base languages (must be kept in sync)**: English (`strings_en.json`) and Simplified Chinese (`strings_zh.json`)
- **Location**: `composeApp/src/commonMain/composeResources/files/i18n/`
- **File format**: JSON
- **Currently supported**: 5+ languages including Chinese (Simplified, Traditional, Cantonese)

### Translation Update Process (GitHub workflow)

When you add or update translations, follow this order:

1. Update both base language files first:
```bash
# edit both files
composeApp/src/commonMain/composeResources/files/i18n/strings_en.json
composeApp/src/commonMain/composeResources/files/i18n/strings_zh.json
```

2. Update other locale files (`strings_*.json`) using the same key set.

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
- `strings_zh.json` - Simplified Chinese
- `strings_zh_tw.json` - Traditional Chinese (Taiwan)
- `strings_zh_hk.json` - Cantonese (Hong Kong)
- `strings_zh_hard.json` - Chinese (Hard - Easter egg)
- `strings_cat.json` - Cat language (Easter egg)

### Contributing Translations

1. **Via Crowdin** (Recommended): Join our Crowdin project for collaborative translation
2. **Via GitHub**: Submit a pull request with your new/updated translation files
3. Include the language name in English and native language in your PR title eg: Add xx(code) localization

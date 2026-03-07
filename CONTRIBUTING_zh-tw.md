# 為 MicYou 做出貢獻

首先，感謝您對為 MicYou 做出貢獻的興趣！我們歡迎所有類型的貢獻，無論是錯誤報告、功能請求、程式碼貢獻還是翻譯。

## 從原始程式碼構建

該項目使用 Kotlin Multiplatform 構建。

**Android 應用（APK）：**
```bash
./gradlew :composeApp:assembleDebug
```

**桌面應用（直接執行）：**
```bash
./gradlew :composeApp:run
```

**構建分發套件：**

**Windows 安裝程式（NSIS）：**
```bash
./gradlew :composeApp:packageWindowsNsis
```

**Windows ZIP 封存：**
```bash
./gradlew :composeApp:packageWindowsZip
```

**Linux DEB 套件：**
```bash
./gradlew :composeApp:packageDeb
```

**Linux RPM 套件：**
```bash
./gradlew :composeApp:packageRpm
```

## 國際化（i18n）

MicYou 支援多種語言，具有強大的翻譯系統。我們歡迎貢獻者將 MicYou 翻譯成您的語言！

### 透過 Crowdin 翻譯（推薦）

最簡單的貢獻翻譯的方式是透過 [Crowdin](https://crowdin.com/project/micyou)。無需本地開發設定：

1. 訪問 [MicYou on Crowdin](https://crowdin.com/project/micyou)
2. 使用您的 GitHub 帳戶註冊或登入
3. 從清單中選擇您的語言
4. 直接在網頁介面中翻譯字串
5. 提交翻譯以供審核

翻譯合併後，將透過 GitHub Actions 自動同步到儲存庫。

### 手動新增新語言

要手動新增新語言：

1. 複製儲存庫：
```bash
git clone https://github.com/LanRhyme/MicYou.git
cd MicYou
```

2. 複製英文翻譯檔案作為範本：
```bash
cp composeApp/src/commonMain/composeResources/files/i18n/strings_en.json composeApp/src/commonMain/composeResources/files/i18n/strings_xx.json
```
將 `xx` 替換為您的語言代碼（例如，法語為 `fr`，西班牙語為 `es`）。

3. 編輯新的 JSON 檔案，翻譯所有字串值，同時保持鍵不變：
```json
{
  "appName": "MicYou",
  "ipLabel": "IP: ",
  ...
}
```

4. 在 [Localization.kt](composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Localization.kt) 中註冊新語言：

查找 `AppLanguage` 列舉並新增您的語言：
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... 現有語言 ...
    French("Français", "fr"),  // 新增這一行
}
```

同時更新 `getStrings()` 函式以處理您的語言：
```kotlin
fun getStrings(language: AppLanguage): AppStrings {
    val langCode = when (language) {
        // ... 現有情況 ...
        AppLanguage.French -> "fr"
        // ...
    }
    // ...
}
```

### 測試翻譯

要在本地測試您的翻譯：

1. 構建並執行桌面應用：
```bash
./gradlew :composeApp:run
```

2. 前往 **設定 → 外觀 → 語言** 並選擇您的新語言

3. 驗證所有字串都已正確翻譯，佈局看起來正確

4. 對於 Android 應用，構建 APK：
```bash
./gradlew :composeApp:assembleDebug
```

### 翻譯工作流程

- **來源語言**：英文（`strings_en.json`）
- **位置**：`composeApp/src/commonMain/composeResources/files/i18n/`
- **檔案格式**：JSON
- **目前支援**：5+ 種語言，包括中文（簡體、繁體、粵語）

### 特殊語言變體

某些語言具有特殊變體：
- `strings_zh.json` - 簡體中文
- `strings_zh_tw.json` - 繁體中文（台灣）
- `strings_zh_hk.json` - 粵語（香港）
- `strings_zh_hard.json` - 中文（困難 - 彩蛋）
- `strings_cat.json` - 貓語言（彩蛋）

### 貢獻翻譯

1. **透過 Crowdin**（推薦）：加入我們的 Crowdin 專案進行協作翻譯
2. **透過 GitHub**：提交包含您的新的／更新的翻譯檔案的請求提取
3. 在您的 PR 標題中包含英文和母語的語言名稱，例如：Add xx(code) localization

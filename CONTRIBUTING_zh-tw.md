# 為 MicYou 做出貢獻

感謝您有興趣為 MicYou 做出貢獻！本指南涵蓋如何從原始程式碼構建應用、新增翻譯和提交變更。

我們歡迎所有類型的貢獻，包括錯誤報告、功能請求、程式碼貢獻以及翻譯。

## 從原始程式碼構建

本專案使用 Kotlin Multiplatform 構建。

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

**macOS DMG 套件:**
```bash
./gradlew :composeApp:packageDmg
```

**不含 JRE 套件:**
```bash
./gradlew :composeApp:packageNoJreAll
```

## 國際化（i18n）

MicYou 使用 Compose Multiplatform Resources 進行本地化。所有使用者可見的字串儲存在 `strings.xml` 檔案中。我們歡迎貢獻者將 MicYou 翻譯成您的母語！

### 新增語言

若要手動新增新語言，請按照以下步驟操作：

1. 複製儲存庫：
```bash
git clone https://github.com/LanRhyme/MicYou.git
cd MicYou
```

2. 建立新的 `strings.xml` 檔案：
```bash
mkdir -p composeApp/src/commonMain/composeResources/values-xx
cp composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-xx/strings.xml
```
將 `xx` 替換為您的語言代碼（例如，法語為 `fr`，西班牙語為 `es`，基於 ISO 639-1 或其他相關標準，如 IETF BCP 47）。

3. 編輯新的 `strings.xml` 檔案，翻譯所有字串值，同時保持鍵不變：
```xml
<resources>
    <string name="appName">MicYou</string>
    <string name="ipLabel">IP : </string>
    <!-- ... -->
</resources>
```

4. 在 [Localization.kt](composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Localization.kt) 檔案中註冊新語言：

查找 `AppLanguage` 列舉並新增您的語言：
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... 現有語言 ...
    French("Français", "fr"),  // 新增這一行
}
```

### 在程式碼中使用字串

```kotlin
// 在 @Composable 上下文中
Text(stringResource(Res.string.myKey))

// 在 suspend 上下文中
val text = getString(Res.string.myKey)

// 帶格式化參數（%s, %d, %1$s 為位置參數）
Text(stringResource(Res.string.myFormattedKey, arg1))
```

### 測試翻譯

若要在本地測試您的翻譯，請按照以下步驟操作：

1. 構建並執行桌面應用：
```bash
./gradlew :composeApp:run
```

2. 前往 **設定 → 外觀 → 語言** 並選擇您的新語言

3. 檢查所有字串顯示正確，佈局沒有裁剪或溢出

4. 對於 Android 應用，構建 APK：
```bash
./gradlew :composeApp:assembleDebug
```

### 翻譯工作流程

- **來源語言（必須保持同步）**：英文（`values/strings.xml`）和簡體中文（`values-zh/strings.xml`）
- **位置**：`composeApp/src/commonMain/composeResources/values*/strings.xml`
- **檔案格式**：Android strings.xml
- **目前已支援**：6 種語言，包括中文（簡體、繁體、粵語）

### 翻譯更新流程（GitHub 工作流程）

當您新增或修改翻譯時，請按照以下順序操作：

1. 先更新兩個來源語言檔案：
```
composeApp/src/commonMain/composeResources/values/strings.xml      (英文)
composeApp/src/commonMain/composeResources/values-zh/strings.xml   (簡體中文)
```

2. 再更新其他語言檔案（`values-*/strings.xml`），確保鍵集合一致。

3. 本地執行翻譯校驗：
```bash
./gradlew checkLocalization
```

4. 建議先執行一次鉤子安裝，讓每次提交前自動檢查：
```bash
./gradlew installGitHooks
```

5. 僅在 `checkLocalization` 通過後再提交。

預提交鉤子會執行 `checkLocalization`，若鍵不一致或值為空會阻止提交。

### 特殊語言變體

某些語言具有特殊變體：
- `values-zh/` - 簡體中文
- `values-zh-rTW/` - 繁體中文（台灣）
- `values-zh-rHK/` - 粵語（香港）
- `values-zh-rSS/` - 中文硬核模式（彩蛋）
- `values-ca/` - 貓貓語（彩蛋）

### 貢獻翻譯

提交包含新增或更新翻譯檔案的拉取請求。

## 提交變更

所有 PR 標題使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

- `feat(i18n): add fr (French) localization`
- `fix: resolve audio crash on Android 14`
- `docs: update build instructions`

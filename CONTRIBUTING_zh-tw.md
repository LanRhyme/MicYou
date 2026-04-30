# 為 MicYou 做出貢獻

首先，感謝您有興趣為 MicYou 做出貢獻！我們歡迎所有類型的貢獻，包括錯誤報告、功能請求、程式碼貢獻以及翻譯。

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

## 國際化（i18n）

MicYou 使用 Compose Multiplatform Resources 進行本地化。所有使用者可見的字串儲存在 `strings.xml` 檔案中。我們歡迎貢獻者將 MicYou 翻譯成您的母語！

### 透過 Crowdin 翻譯（推薦）

貢獻翻譯最簡單的方式是透過 [Crowdin](https://crowdin.com/project/micyou)，無需本地開發環境：

1. 訪問 [MicYou on Crowdin](https://crowdin.com/project/micyou)
2. 使用您的 GitHub 帳戶註冊或登入
3. 從清單中選擇您的語言
4. 直接在網頁介面中翻譯字串
5. 提交翻譯以供審核

翻譯被合併後，將透過 GitHub Actions 自動同步到程式碼倉庫。

### 手動新增新語言

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
將 `xx` 替換為您的語言代碼（例如，法語為 `fr`，西班牙語為 `es`）。

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

3. 驗證所有字串都已正確翻譯，佈局看起來正確

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

1. **透過 Crowdin**（推薦）：加入我們的 Crowdin 專案進行協作翻譯。
2. **透過 GitHub**：提交包含您的新的或更新的翻譯檔案的拉取請求。
3. 在您的 PR 標題中包含英文和母語的語言名稱，例如：Add xx(code) localization。

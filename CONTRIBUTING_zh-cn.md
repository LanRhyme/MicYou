# 为 MicYou 做出贡献

感谢您有兴趣为 MicYou 做出贡献！本指南涵盖如何从源代码构建应用、添加翻译和提交更改。

我们欢迎所有类型的贡献，包括错误报告、功能请求、代码贡献以及翻译。

## 从源代码构建

本项目使用 Kotlin Multiplatform 构建。

**Android 应用（APK）：**
```bash
./gradlew :composeApp:assembleDebug
```

**桌面应用（直接运行）：**
```bash
./gradlew :composeApp:run
```

**构建分发包：**

**Windows 安装程序（NSIS）：**
```bash
./gradlew :composeApp:packageWindowsNsis
```

**Windows ZIP 存档：**
```bash
./gradlew :composeApp:packageWindowsZip
```

**Linux DEB 包：**
```bash
./gradlew :composeApp:packageDeb
```

**Linux RPM 包：**
```bash
./gradlew :composeApp:packageRpm
```

**macOS DMG 包:**
```bash
./gradlew :composeApp:packageDmg
```

**无 JRE 包:**
```bash
./gradlew :composeApp:packageNoJreAll
```

## 国际化（i18n）

MicYou 使用 Compose Multiplatform Resources 进行本地化。所有用户可见的字符串存储在 `strings.xml` 文件中。我们欢迎贡献者将 MicYou 翻译成您的母语！

### 添加新语言

若要手动添加新语言，请按以下步骤操作：

1. 克隆存储库：
```bash
git clone https://github.com/LanRhyme/MicYou.git
cd MicYou
```

2. 创建新的 `strings.xml` 文件：
```bash
mkdir -p composeApp/src/commonMain/composeResources/values-xx
cp composeApp/src/commonMain/composeResources/values/strings.xml composeApp/src/commonMain/composeResources/values-xx/strings.xml
```
将 `xx` 替换为您的语言代码（例如，法语为 `fr`，西班牙语为 `es`，基于 ISO 639-1 或其他相关标准，例如 IETF BCP 47）。

3. 编辑新的 `strings.xml` 文件，翻译所有字符串值，同时保持键不变：
```xml
<resources>
    <string name="appName">MicYou</string>
    <string name="ipLabel">IP : </string>
    <!-- ... -->
</resources>
```

4. 在 [Localization.kt](composeApp/src/commonMain/kotlin/com/lanrhyme/micyou/Localization.kt) 中注册新语言：

查找 `AppLanguage` 枚举并添加您的语言：
```kotlin
enum class AppLanguage(val label: String, val code: String) {
    // ... 现有语言 ...
    French("Français", "fr"),  // 添加这一行
}
```

### 在代码中使用字符串

```kotlin
// 在 @Composable 上下文中
Text(stringResource(Res.string.myKey))

// 在 suspend 上下文中
val text = getString(Res.string.myKey)

// 带格式化参数（%s, %d, %1$s 为位置参数）
Text(stringResource(Res.string.myFormattedKey, arg1))
```

### 测试翻译

若要在本地测试您的翻译，请按照以下步骤操作：

1. 构建并运行桌面应用：
```bash
./gradlew :composeApp:run
```

2. 转至 **设置 → 外观 → 语言** 并选择您的新语言

3. 检查所有字符串显示正确，布局没有裁剪或溢出

4. 对于 Android 应用，构建 APK：
```bash
./gradlew :composeApp:assembleDebug
```

### 翻译工作流

- **母语言（必须保持同步）**：英文（`values/strings.xml`）和简体中文（`values-zh/strings.xml`）
- **位置**：`composeApp/src/commonMain/composeResources/values*/strings.xml`
- **文件格式**：Android strings.xml
- **目前已支持**：6 种语言，包括中文（简体、繁体、粤语）

### 翻译更新流程（GitHub 工作流）

当你新增或修改翻译时，请按以下顺序操作：

1. 先更新两个母语言文件：
```
composeApp/src/commonMain/composeResources/values/strings.xml      (英文)
composeApp/src/commonMain/composeResources/values-zh/strings.xml   (简体中文)
```

2. 再更新其他语言文件（`values-*/strings.xml`），确保键集合一致。

3. 本地运行翻译校验：
```bash
./gradlew checkLocalization
```

4. 建议先执行一次钩子安装，让每次提交前自动检查：
```bash
./gradlew installGitHooks
```

5. 仅在 `checkLocalization` 通过后再提交。

预提交钩子会执行 `checkLocalization`，若键不一致或值为空会阻止提交。

### 特殊语言变体

某些语言具有特殊变体：
- `values-zh/` - 简体中文
- `values-zh-rTW/` - 繁体中文（台湾）
- `values-zh-rHK/` - 粤语（香港）
- `values-zh-rSS/` - 中文硬核模式（彩蛋）
- `values-ca/` - 猫猫语（彩蛋）

### 贡献翻译

提交包含新增或更新翻译文件的拉取请求。

## 提交更改

所有 PR 标题使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

- `feat(i18n): add fr (French) localization`
- `fix: resolve audio crash on Android 14`
- `docs: update build instructions`

# 为 MicYou 做出贡献

首先，感谢您有兴趣为 MicYou 做出贡献！我们欢迎所有类型的贡献，包括错误报告、功能请求、代码贡献以及翻译。

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

## 国际化（i18n）

MicYou 使用 Compose Multiplatform Resources 进行本地化。所有用户可见的字符串存储在 `strings.xml` 文件中。我们欢迎贡献者将 MicYou 翻译成您的母语！

### 通过 Crowdin 翻译（推荐）

贡献翻译最简单的方式是通过 [Crowdin](https://crowdin.com/project/micyou)，无需本地开发环境：

1. 访问 [MicYou on Crowdin](https://crowdin.com/project/micyou)
2. 使用您的 GitHub 账户注册或登录
3. 从列表中选择您的语言
4. 直接在网页界面中翻译字符串
5. 提交翻译以供审核

翻译被合并后，将通过 GitHub Actions 自动同步到代码仓库。

### 手动添加新语言

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
将 `xx` 替换为您的语言代码（例如，法语为 `fr`，西班牙语为 `es`）。

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

3. 验证所有字符串都已正确翻译，布局看起来正确

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

1. **通过 Crowdin**（推荐）：加入我们的 Crowdin 项目进行协作翻译。
2. **通过 GitHub**：提交包含您的新的或更新的翻译文件的拉取请求。
3. 请在 PR 标题中包含英文及母语的语言名称，例如：Add xx(code) localization。

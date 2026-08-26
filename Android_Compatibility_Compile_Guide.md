# Android 低版本兼容性编译说明

> 适用版本：v1.1.5 / v2.0.1  
> 目标设备：Android 5.0+ (API 21+) / 32 位设备  
> 设计原则：**非破坏性** — 不修改原版编译逻辑，通过 Gradle 属性条件启用兼容模式

---

## 快速开始

### 前置条件：JDK 17 安装

AGP 8.x 以上需要 JDK 11+（v2.0.1 原版 AGP 9.x 需要 JDK 17+）。推荐安装 Eclipse Temurin JDK 17 LTS：

下载地址：https://adoptium.net/temurin/releases/?version=17&arch=x64

安装时勾选 "Set JAVA_HOME variable" 和 "Add to PATH"。安装后 Gradle 会自动使用，不影响系统已有的 Java 8。

验证安装：
```bash
java -version
# 应显示 openjdk version "17.x.x"
```

如需在项目级指定 JDK 路径（不影响系统环境），在 `gradle.properties` 中取消注释：
```properties
org.gradle.java.home=C\:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot
```

### 前置条件：Android SDK

`local.properties` 中需指定 SDK 路径：
```properties
sdk.dir=D\:\\Android\\android-sdk-windows
```

需安装的 SDK 组件：
- Platform: android-36（compileSdk 36）
- Build Tools: 36.0.0 或更高

---

### 原版构建（与上游完全一致）

```bash
./gradlew :composeApp:assembleDebug
```

不加任何额外参数，编译结果、依赖版本、SDK 配置与原版完全相同。

### 兼容版构建（支持 Android 5.0+）

**重要：在 PowerShell 中必须给参数加引号，否则点号会被截断：**

```powershell
# PowerShell — 必须用引号
./gradlew :composeApp:assembleDebug "-Pmicyou.androidCompat=true"
```

```bash
# CMD / Bash — 不需要引号
./gradlew :composeApp:assembleDebug -Pmicyou.androidCompat=true
```

输出路径：
```
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

---

## 兼容模式做了什么

启用 `-Pmicyou.androidCompat=true` 后，以下配置会自动切换到兼容版本：

### v2.0.1 版本对照

| 配置项 | 原版 | 兼容版 |
|--------|------|--------|
| AGP | 9.3.1 | 8.9.1 |
| Kotlin | 2.4.10 | 2.1.20 |
| minSdk | 24 (Android 7.0) | 21 (Android 5.0) |
| targetSdk | 36 | 29 |
| Compose BOM | 2026.05.00 | 2024.09.00 |
| materialKolor | 5.0.0-alpha07 | 1.7.1 |
| haze | 1.7.2 | 1.0.0 |
| Ktor | 3.5.0 | 3.0.3 |
| kotlinx-coroutines | 1.11.0 | 1.9.0 |
| kotlinx-serialization | 1.11.0 | 1.7.3 |
| kotlinx-datetime | 0.8.0 | 0.7.1 |
| androidx.activity | 1.12.2 | 1.8.2 |
| androidx.lifecycle | 2.9.4 | 2.8.0 |
| androidx.core | 1.17.0 | 1.12.0 |
| MultiDex | 关闭 | 开启 |
| coreLibraryDesugaring | 关闭 | 开启 |
| JVM target | 11 | 1.8 |
| 32位 ABI | 无 | armeabi-v7a, x86 |
| 代码混淆 (release) | 开启 | 关闭 |

### v1.1.5 版本对照

| 配置项 | 原版 | 兼容版 |
|--------|------|--------|
| minSdk | 24 (Android 7.0) | 21 (Android 5.0) |
| targetSdk | 36 | 29 |
| Compose Multiplatform | 1.10.1 | 1.7.3 |
| Compose Material3 | 1.10.0-alpha05 | 1.7.3 |
| Kotlin | 2.2.20 | 2.1.20 |
| Ktor | 3.4.0 | 3.0.3 |
| kotlinx-coroutines | 1.10.2 | 1.9.0 |
| kotlinx-serialization | 1.8.1 | 1.7.3 |
| androidx.activity | 1.12.2 | 1.8.2 |
| androidx.lifecycle | 2.9.6 | 2.8.0 |
| androidx.core | 1.17.0 | 1.12.0 |
| MultiDex | 关闭 | 开启 |
| coreLibraryDesugaring | 关闭 | 开启 |

### 运行时兼容（不依赖编译开关，始终生效）

- **设置 UI 格式过滤**：API < 23 时设置界面不显示 "32-bit Float" 选项，避免用户选择不支持的格式导致两端格式不匹配
- **默认格式自动选择**：API < 23 时默认 PCM_16BIT，API >= 23 时默认 PCM_FLOAT（v2.0.1），Desktop 始终 PCM_FLOAT
- **已保存格式校验**：从旧设备迁移的设置（如 PCM_FLOAT）在低 API 设备上自动回退到 PCM_16BIT
- **PCM_FLOAT 降级**：API < 23 时即使通过其他方式传入 PCM_FLOAT，运行时也会降级为 PCM_16BIT
- **TileService 守卫**：`startActivityAndCollapse` 按 API 级别分支调用
- **全局异常处理**：`MicYouApplication` 注册 UncaughtExceptionHandler，崩溃时写入日志文件
- **MultiDex 反射加载**：通过反射调用 `MultiDex.install()`，无依赖时优雅跳过

---

## 技术实现原理

### 版本切换机制（v2.0.1）

v2.0.1 通过 `settings.gradle.kts` 中的 `dependencyResolutionManagement.versionCatalogs` 覆盖版本号：

1. 检测 `-Pmicyou.androidCompat` 命令行参数（通过 `gradle.startParameter.projectProperties`）
2. 在 `versionCatalogs` 块中覆盖 `agp`、`kotlin` 等版本号
3. 所有 `alias(libs.plugins.*)` 引用自动使用降级后的版本
4. `composeApp/build.gradle.kts` 中的条件判断控制 minSdk、MultiDex、desugaring 等

### 版本切换机制（v1.1.5）

v1.1.5 通过 `build.gradle.kts` 中的 `resolutionStrategy` 和条件判断实现。

---

## 修改的文件

### v2.0.1 改动文件

| 文件 | 改动说明 |
|------|----------|
| `settings.gradle.kts` | 新增：兼容模式检测 + versionCatalogs 版本覆盖 |
| `gradle/libs.versions.toml` | 恢复原版版本，新增 `compat-*` 系列兼容版本条目 |
| `composeApp/build.gradle.kts` | 添加 `androidCompat` 条件判断，控制 SDK / 依赖 / MultiDex / desugaring / JVM target |
| `composeApp/src/main/kotlin/.../MicYouApplication.kt` | MultiDex 改为反射调用，保留崩溃日志记录 |
| `composeApp/src/main/AndroidManifest.xml` | 添加 `android:name`、`tools:ignore`、`tools:overrideLibrary` |
| `gradle.properties` | 添加 `org.gradle.java.home` 配置说明（默认注释） |
| `local.properties` | SDK 路径指向本地 Android SDK |

### v1.1.5 改动文件

| 文件 | 改动说明 |
|------|----------|
| `gradle/libs.versions.toml` | 恢复原版版本，新增 `compat-*` 系列兼容版本条目 |
| `composeApp/build.gradle.kts` | 添加 `androidCompat` 条件判断 |
| `composeApp/src/androidMain/kotlin/.../MicYouApplication.kt` | 新建，MultiDex 反射调用 + 全局异常处理 |
| `composeApp/src/androidMain/kotlin/.../AudioEngine.android.kt` | PCM_FLOAT 降级 + AudioRecord.read API 检查 |
| `composeApp/src/androidMain/kotlin/.../MicYouTileService.kt` | startActivityAndCollapse API 级别守卫 |
| `composeApp/src/androidMain/AndroidManifest.xml` | 添加 `android:name`、高版本权限 `tools:ignore` |

---

## 非破坏性设计说明

1. **默认行为不变**：不传 `-Pmicyou.androidCompat=true` 时，所有版本号、依赖、配置与原版完全一致
2. **新增而非修改**：`libs.versions.toml` 中新增 `compat-*` 条目，原版本值不动
3. **版本覆盖而非替换**：`settings.gradle.kts` 通过 `versionCatalogs.version()` 覆盖版本号，不修改原 TOML 文件
4. **运行时安全**：`MicYouApplication` 通过反射调用 MultiDex，无该依赖时静默跳过
5. **代码层兼容**：PCM_FLOAT 降级、TileService 守卫等是纯运行时检查，不影响编译
6. **编译环境隔离**：JDK 路径通过 `org.gradle.java.home` 项目级配置，不影响系统 Java 环境

---

## PowerShell 注意事项

在 PowerShell 中使用 `-P` 参数时，如果参数名包含点号（如 `micyou.androidCompat`），**必须用引号包裹整个参数**：

```powershell
# 正确 — 引号包裹
./gradlew :composeApp:assembleDebug "-Pmicyou.androidCompat=true"

# 错误 — 不加引号，PowerShell 会把 micyou.androidCompat 截断为 micyou
./gradlew :composeApp:assembleDebug -Pmicyou.androidCompat=true
```

在 CMD 或 Bash 中不需要引号。

---

## 扩展：兼容更低版本

如需进一步向下兼容（例如 API 19 / Android 4.4），可按以下步骤扩展：

1. 在 `libs.versions.toml` 中新增 `compat-low-*` 系列版本条目
2. 在 `settings.gradle.kts` 中添加新的属性判断，例如 `-Pmicyou.androidCompat=api19`
3. 在 `composeApp/build.gradle.kts` 中添加对应分支的条件逻辑
4. 在代码中添加更多运行时 API 级别检查

建议的参数设计：

```
-Pmicyou.androidCompat=api21   # 默认，兼容 Android 5.0+
-Pmicyou.androidCompat=api19   # 扩展，兼容 Android 4.4+
```

当前实现使用 `hasProperty` 检测属性是否存在（布尔模式），如需多档兼容，可改为读取属性值：

```kotlin
val compatLevel = project.properties["micyou.androidCompat"]?.toString()
val androidCompat = compatLevel != null
val isApi19 = compatLevel == "api19"
```

---

## 已知限制

- 兼容版 targetSdk = 29，不满足 Google Play 最新 target API 要求（仅用于侧载/内部测试）
- Android 5.0 设备内存通常较小（1-2GB），Compose 应用可能存在性能差异
- 兼容版未经过完整的功能回归测试，建议在目标设备上验证核心功能
- v2.0.1 原版 AGP 9.3.1 需要 Gradle 9.5.0+，兼容模式 AGP 8.9.1 只需 Gradle 8.x
- filekit 库在兼容模式下未降级，如遇 minSdk 冲突可在 resolutionStrategy 中补充 force

---

## 验证方法

在低版本设备上安装 APK 后，依次验证：

1. 应用能否正常启动，不出现白屏或闪退
2. 点击连接按钮，音频服务能否正常启动
3. 音频流能否正常传输到 PC 端
4. 快捷设置磁贴（Tile）能否正常显示和点击
5. 切换各种音频格式（PCM_16BIT / PCM_FLOAT）是否正常

如遇崩溃，可通过以下方式查看日志：

```bash
# 查看全局崩溃日志
adb logcat -s MicYouApplication

# 或查看设备上的日志文件
adb shell run-as com.lanrhyme.micyou cat files/crash/*.txt
```

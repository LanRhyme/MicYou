# Android 低版本兼容性编译说明

> 适用版本：v2.0.1（基于 upstream/master 最新代码）
> 目标设备：Android 5.0+ (API 21+) / 32 位设备
> 设计原则：**非破坏性** — 不修改原版编译逻辑，通过 Gradle 属性条件启用兼容模式

---

## 快速开始

### 前置条件：JDK 17 安装

兼容模式使用 AGP 8.9.1，需要 JDK 17+。推荐安装 Eclipse Temurin JDK 17 LTS：

下载地址：https://adoptium.net/temurin/releases/?version=17&arch=x64

安装时勾选 "Set JAVA_HOME variable" 和 "Add to PATH"。安装后 Gradle 会自动使用。

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

不传任何兼容参数，编译结果、依赖版本、SDK 配置与上游原版完全相同。

### 兼容版构建（支持 Android 5.0+）

通过 `-Pmicyou.androidCompat=<level>` 参数指定兼容级别，当前支持：

| 级别 | 最低 Android 版本 | 说明 |
|------|-------------------|------|
| `api21` | Android 5.0 (API 21) | 当前已实现，推荐使用 |

**重要：在 PowerShell 中必须给参数加引号，否则点号会被截断：**

```powershell
# PowerShell — 必须用引号
./gradlew :composeApp:assembleDebug "-Pmicyou.androidCompat=api21"
```

```bash
# CMD / Bash — 不需要引号
./gradlew :composeApp:assembleDebug -Pmicyou.androidCompat=api21
```

输出路径：
```
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

---

## 兼容模式做了什么

启用 `-Pmicyou.androidCompat=api21` 后，以下配置会自动切换到兼容版本：

### 编译时配置对照

| 配置项 | 原版 (upstream) | 兼容版 (api21) |
|--------|-----------------|----------------|
| AGP | 9.3.1 | 8.9.1 |
| Kotlin | 2.4.10 | 2.3.10 |
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

### 运行时兼容（与编译开关无关，始终生效）

所有高版本 API 调用均通过 `Build.VERSION.SDK_INT` 运行时检查，低版本设备上自动走降级路径：

| 兼容点 | 高版本行为 | 低版本降级 | 涉及文件 |
|--------|-----------|-----------|----------|
| AudioRecord 浮点读取 | `read(float[], ..., READ_NON_BLOCKING)` (API 23+) | `read(ByteBuffer)` + `asFloatBuffer()` 间接读取 | `AudioEngine.kt` |
| AudioRecord 阻塞模式 | `read(..., READ_NON_BLOCKING)` (API 23+) | 3 参数阻塞版本 `read(buffer, 0, size)` | `AudioEngine.kt` |
| 前台服务启动 | `startForegroundService()` (API 26+) | `startService()` + 内部 `startForeground()` | `MainActivity.kt` |
| 通知渠道 | `NotificationChannel` + `createNotificationChannel()` (API 26+) | 跳过渠道创建，直接发通知 | `AudioService.kt` |
| PendingIntent 标志 | `FLAG_IMMUTABLE` (API 23+) | 仅 `FLAG_UPDATE_CURRENT` | `AudioService.kt`, `RestartReceiver.kt` |
| 闹钟精确唤醒 | `setExactAndAllowWhileIdle()` (API 23+) | `setExact()` | `AudioService.kt` |
| startForeground 类型 | 3 参数版本含 foregroundServiceType (API 29+) | 2 参数版本 | `AudioService.kt` |
| 通知权限 | `POST_NOTIFICATIONS` 运行时请求 (API 33+) | 跳过，安装时自动授予 | `PermissionDialog.kt` |
| TileService | `startActivityAndCollapse()` 2 参数版 (API 34+) | 1 参数版本 | `MicYouTileService.kt` |
| PCM_FLOAT 格式 | 默认使用 PCM_FLOAT (API 23+) | 默认回退到 PCM_16BIT | `AudioEngine.kt` |
| MultiDex | 无需（高版本 ART 原生支持） | 反射调用 `MultiDex.install()` | `MicYouApplication.kt` |
| Material3 颜色角色 | 使用动态颜色角色 (API 31+) | 降级为近似色值 | `ColorSchemeCompat.kt` |

### Source Set 桥接层

对于 API 差异较大、无法用简单版本判断解决的库（如 haze、materialKolor），采用 source set 桥接层方案：

```
composeApp/src/
├── main/          # 通用代码，从桥接包导入（如 com.lanrhyme.micyou.ui.compose.haze）
├── normal/        # 正常模式：桥接层委托给真实库（haze 1.7.2 / materialKolor 5.x）
└── compat/        # 兼容模式：桥接层用降级方案（半透明回退 / materialKolor 1.7.1）
```

编译时根据 `micyou.androidCompat` 参数选择 `normal` 或 `compat` 源集加入构建，`main` 中的代码无需修改。

---

## 技术实现原理

### 参数切换机制

1. 在 `settings.gradle.kts` 中检测 `-Pmicyou.androidCompat` 参数的值
2. 读取值为兼容级别字符串（如 `api21`），为空则为正常模式
3. 在 `versionCatalogs` 块中按级别覆盖 `agp`、`kotlin` 等版本号
4. 所有 `alias(libs.plugins.*)` 引用自动使用降级后的版本
5. `composeApp/build.gradle.kts` 中的条件判断控制 minSdk、MultiDex、desugaring、JVM target、source sets 等
6. `resolutionStrategy.force()` + `eachDependency` 强制降级传递依赖，确保 Compose 等库版本一致

### 多级别扩展设计

参数采用数值/字符串级别而非布尔值，便于未来扩展更低版本：

```
-Pmicyou.androidCompat=api21   # 已实现，兼容 Android 5.0+
-Pmicyou.androidCompat=api19   # 预留，兼容 Android 4.4+
```

新增级别时只需在 `settings.gradle.kts` 和 `composeApp/build.gradle.kts` 中添加对应分支，无需改动现有逻辑。

---

## 修改的文件

### 构建配置

| 文件 | 改动说明 |
|------|----------|
| `settings.gradle.kts` | 新增：兼容级别检测 + versionCatalogs 版本覆盖（多档设计） |
| `composeApp/build.gradle.kts` | 添加 `androidCompat` / `isApi21Compat` 条件判断，控制 SDK / 依赖 / MultiDex / desugaring / JVM target / source sets / resolutionStrategy |
| `gradle/libs.versions.toml` | 新增 `compat-*` 系列兼容版本条目（原版本值不动） |

### 运行时兼容代码

| 文件 | 改动说明 |
|------|----------|
| `composeApp/src/main/kotlin/.../MicYouApplication.kt` | MultiDex 改为反射调用，保留崩溃日志记录 |
| `composeApp/src/main/kotlin/.../audio/AudioEngine.kt` | AudioRecord 浮点读取 + 非阻塞读取的 API 23- 降级，PCM_FLOAT 默认格式判断 |
| `composeApp/src/main/kotlin/.../service/AudioService.kt` | 通知渠道、PendingIntent、闹钟、startForeground 等多处 API 级别守卫 |
| `composeApp/src/main/kotlin/.../MainActivity.kt` | 前台服务启动的 API 26- 降级 |
| `composeApp/src/main/kotlin/.../service/RestartReceiver.kt` | PendingIntent.FLAG_IMMUTABLE 的 API 23- 降级 |
| `composeApp/src/main/kotlin/.../service/MicYouTileService.kt` | startActivityAndCollapse 的 API 级别守卫 |
| `composeApp/src/main/kotlin/.../ui/dialog/PermissionDialog.kt` | POST_NOTIFICATIONS 权限的 API 33+ 条件 |
| `composeApp/src/main/kotlin/.../ui/compose/material3/ColorSchemeCompat.kt` | Material3 颜色角色兼容扩展 |

### Source Set 桥接层

| 文件 | 改动说明 |
|------|----------|
| `composeApp/src/normal/kotlin/.../ui/compose/haze/HazeBridge.kt` | 正常模式：haze 库桥接（委托给 haze 1.7.2） |
| `composeApp/src/compat/kotlin/.../ui/compose/haze/HazeBridge.kt` | 兼容模式：haze 半透明背景回退实现 |

### 其他

| 文件 | 改动说明 |
|------|----------|
| `composeApp/src/main/AndroidManifest.xml` | 添加 `android:name`、高版本权限 `tools:ignore`、`tools:overrideLibrary` 处理 minSdk 冲突 |

---

## 非破坏性设计说明

1. **默认行为不变**：不传 `-Pmicyou.androidCompat` 时，所有版本号、依赖、配置与上游原版完全一致
2. **新增而非修改**：`libs.versions.toml` 中新增 `compat-*` 条目，原版本值不动
3. **版本覆盖而非替换**：`settings.gradle.kts` 通过 `versionCatalogs.version()` 覆盖版本号，不修改原 TOML 文件
4. **运行时安全**：`MicYouApplication` 通过反射调用 MultiDex，无该依赖时静默跳过
5. **代码层兼容**：所有高版本 API 调用都有 `Build.VERSION.SDK_INT` 守卫，低版本自动降级
6. **Source Set 隔离**：normal/compat 两套源集独立，main 只依赖桥接接口，不直接引用具体库实现
7. **编译环境隔离**：JDK 路径通过 `org.gradle.java.home` 项目级配置，不影响系统 Java 环境
8. **参数可扩展**：数值型参数支持多档兼容级别，新增级别不破坏现有逻辑

---

## PowerShell 注意事项

在 PowerShell 中使用 `-P` 参数时，如果参数名包含点号（如 `micyou.androidCompat`），**必须用引号包裹整个参数**：

```powershell
# 正确 — 引号包裹
./gradlew :composeApp:assembleDebug "-Pmicyou.androidCompat=api21"

# 错误 — 不加引号，PowerShell 会把 micyou.androidCompat 截断为 micyou
./gradlew :composeApp:assembleDebug -Pmicyou.androidCompat=api21
```

在 CMD 或 Bash 中不需要引号。

---

## 已知限制

- 兼容版 targetSdk = 29，不满足 Google Play 最新 target API 要求（仅用于侧载/内部测试）
- Android 5.0 设备内存通常较小（1-2GB），Compose 应用可能存在性能差异
- 兼容版 release 构建关闭了代码混淆，包体积较大
- haze 效果在兼容模式下降级为半透明背景，无实时模糊
- filekit 库在兼容模式下未降级，如遇 minSdk 冲突可在 resolutionStrategy 中补充 force
- PC 端监控面板的抖动、丢包率、采样率、比特率统计仅在 WiFi（UDP）模式下可用，USB/TCP 模式下这些值始终为 0（上游原版行为，非兼容模式导致）

---

## 验证方法

在低版本设备上安装 APK 后，依次验证：

1. 应用能否正常启动，不出现白屏或闪退
2. 点击连接按钮，音频服务能否正常启动
3. 音频流能否正常传输到 PC 端
4. 快捷设置磁贴（Tile）能否正常显示和点击
5. 切换各种音频格式（PCM_16BIT / PCM_FLOAT）是否正常
6. 通知能否正常显示和交互
7. 应用被杀死后能否自动重启

如遇崩溃，可通过以下方式查看日志：

```bash
# 查看全局崩溃日志
adb logcat -s MicYouApplication

# 或查看设备上的日志文件
adb shell run-as com.lanrhyme.micyou cat files/crash/*.txt
```

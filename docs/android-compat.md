# Android 低版本兼容构建指南

MicYou Android 客户端默认目标为 Android 7.0+ (API 24+)。针对 Android 5.0+ (API 21+) 及 32 位老旧设备，项目提供了按需启用的兼容构建模式。

---

## 环境准备

- **JDK**: JDK 17 或更高版本，并配置 `JAVA_HOME` 环境变量
- **Android SDK**: 需安装 Android SDK Platform 36 及 Build-Tools 36.0.0+，并配置 `ANDROID_HOME` 环境变量

---

## 构建命令

### 标准版本（默认）

适用于 Android 7.0+ (API 24+)：

```bash
./gradlew :composeApp:assembleDebug
```

### 兼容版本（API 21+ / Android 5.0+）

通过 `-Pmicyou.androidCompat=api21` 参数开启兼容构建模式：

```bash
# Bash / Zsh / Linux / macOS
./gradlew :composeApp:assembleDebug -Pmicyou.androidCompat=api21

# PowerShell (注意参数含点号，必须使用双引号包裹)
./gradlew :composeApp:assembleDebug "-Pmicyou.androidCompat=api21"
```

构建生成的 APK 路径：
```text
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

---

## 兼容架构与降级机制

当指定 `-Pmicyou.androidCompat=api21` 时，构建配置与运行时会自动切换到兼容流水线：

### 1. Source Set 桥接层设计

针对依赖库在低版本 API 与现代版本之间的接口断层（如 Haze 动态模糊与 MaterialKolor），项目采用了 Source Set 桥接策略：

```text
composeApp/src/
├── main/          # 通用业务代码，统一从桥接包导入（com.lanrhyme.micyou.ui.compose.haze）
├── normal/        # 默认模式：桥接层委托给现代库实现（Haze 1.7+ / MaterialKolor 5.x）
└── compat/        # 兼容模式：桥接层采用低版本降级方案（半透明背景回退 / MaterialKolor 1.7.x）
```

Gradle 构建时根据 `-Pmicyou.androidCompat` 参数动态决定将 `normal` 还是 `compat` 目录挂载为源码集，保证 `main/` 业务逻辑无需条件编译分支。

### 2. 运行时 API 动态降级

针对 Android 运行时的 API 差异，核心模块内置了 `Build.VERSION.SDK_INT` 级别的运行时守卫：

| 模块 | 兼容点 | 现代版本行为 (API 24+) | 兼容版回退行为 (API 21-23) |
| --- | --- | --- | --- |
| `AudioEngine.kt` | 采样格式与读取 | `PCM_FLOAT`，`read(float[], ..., READ_NON_BLOCKING)` | 回退至 `PCM_16BIT`，采用 3 参数阻塞读取 |
| `AudioService.kt` | 前台服务 | `startForegroundService()`，带类型参数 | 回退至 `startService()` + 传统 `startForeground()` |
| `AudioService.kt` | 通知与唤醒 | 通知渠道、`FLAG_IMMUTABLE`、精确唤醒 | 跳过通知渠道，采用 `FLAG_UPDATE_CURRENT` |
| `MicYouTileService` | 快捷磁贴 | 2 参数版 `startActivityAndCollapse` | 1 参数兼容版本 |
| `Application` | 64K 方法数 | 高版本 ART 原生 MultiDex | 反射调用 `MultiDex.install()` |
| `ColorScheme` | 动态取色 | Material 3 动态色彩角色 (API 31+) | 回退至静态近似色盘 |

---

## 已知限制

- **Target API**: 兼容模式 `targetSdk` 为 29，主要用于侧载或老旧设备测试，无法直接上架 Google Play。
- **性能**: Android 5.x/6.x 设备的硬件性能与内存较有限，Compose 渲染在极低配设备上可能存在帧率波动。
- **发布混淆**: 兼容模式下的 Release 构建默认关闭混淆以保障老旧 ART/Dalvik 运行稳定性，包体积相对较大。

---

## 调试与排查

安装至老旧设备后若出现异常，可通过 ADB 抓取运行日志：

```bash
# 查看崩溃与异常日志
adb logcat -s MicYouApplication AudioEngine
```

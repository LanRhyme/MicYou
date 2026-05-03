# MicYou 项目规则

## 项目概述

MicYou 是一个 Kotlin Multiplatform (KMP) 项目，支持 Android (Mobile) 和 JVM Desktop 平台。项目使用 Compose Multiplatform 构建 UI。

## 技术栈

- **语言**: Kotlin 2.2.20
- **UI框架**: Compose Multiplatform 1.7.3
- **构建系统**: Gradle (Kotlin DSL)
- **序列化**: kotlinx-serialization

## 项目结构

```
composeApp/
├── src/
│   ├── commonMain/     # 共享代码
│   ├── androidMain/    # Android 平台代码
│   └── jvmMain/        # JVM Desktop 平台代码
└── build.gradle.kts
```

## 插件系统

MicYou 支持插件扩展功能。插件开发相关文档位于 `docs/plugin-api/` 目录。

### 插件 API 核心接口

- `Plugin` - 插件主接口，定义生命周期方法
- `PluginManifest` - 插件元数据清单
- `PluginContext` - 插件运行上下文
- `PluginUIProvider` - UI 组件提供者
- `PluginSettingsProvider` - 设置页面提供者

### 插件开发规范

1. **插件 ID**: 使用反向域名格式，如 `com.example.myplugin`
2. **版本号**: 遵循语义化版本规范 (SemVer)
3. **权限声明**: 在 `plugin.json` 中声明所需权限
4. **平台支持**: 通过 `platform` 字段指定支持的平台

### 可用权限

| 权限 ID | 说明 |
|---------|------|
| `storage` | 文件存储访问 |
| `network` | 网络访问 |
| `camera` | 摄像头访问 |
| `microphone` | 麦克风访问 |

## 代码规范

### Kotlin 代码风格

- 使用 4 空格缩进
- 遵循 Kotlin 官方编码规范
- 使用 meaningful 命名

### Compose UI 规范

- 使用 Material3 组件
- 遵循 Compose 最佳实践
- 使用 `Modifier` 链式调用

### 本地化

- 多语言字符串文件位于 `composeApp/src/commonMain/composeResources/files/i18n/`
- 支持 30+ 种语言
- 使用 `Localization` 类访问本地化字符串

## 构建命令

```bash
# 构建 Android APK
./gradlew assembleDebug

# 构建 Desktop 应用
./gradlew composeDesktopRun

# 运行测试
./gradlew test
```

## 相关文档

- 插件 API 快速开始: `docs/plugin-api/README.md`
- API 参考文档: `docs/plugin-api/api-reference.md`
- 插件格式规范: `docs/plugin-api/plugin-format.md`
- 权限系统: `docs/plugin-api/permissions.md`
- 最佳实践: `docs/plugin-api/best-practices.md`

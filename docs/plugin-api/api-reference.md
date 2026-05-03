# API 参考文档

本文档详细描述 MicYou Plugin API 的所有接口、类和方法。

## 目录

- [Plugin](#plugin)
- [PluginManifest](#pluginmanifest)
- [PluginContext](#plugincontext)
- [PluginHost](#pluginhost)
- [PluginDataChannel](#plugindatachannel)
- [PluginInfo](#plugininfo)
- [PluginPlatform](#pluginplatform)
- [PluginUIProvider](#pluginuiprovider)
- [PluginSettingsProvider](#pluginsettingsprovider)
- [AudioEffectProvider](#audioeffectprovider)
- [AudioEffectPlugin](#audioeffectplugin)
- [PluginLocalization](#pluginlocalization)
- [PluginLocalizationProvider](#pluginlocalizationprovider)
- [数据类型](#数据类型)

---

## Plugin

插件主接口，所有插件必须实现此接口。

```kotlin
interface Plugin {
    val manifest: PluginManifest
    fun onLoad(context: PluginContext) {}
    fun onEnable() {}
    fun onDisable() {}
    fun onUnload() {}
}
```

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `manifest` | `PluginManifest` | 插件元数据清单，包含插件的基本信息 |

### 方法

#### onLoad

```kotlin
fun onLoad(context: PluginContext)
```

插件加载时调用。这是插件初始化的最佳时机。

**参数：**
- `context` - 插件上下文，提供运行环境访问能力

**调用时机：** 插件包被解析并验证通过后，在插件实例创建后立即调用。

**示例：**
```kotlin
override fun onLoad(context: PluginContext) {
    this.context = context
    context.log("Plugin ${manifest.name} loaded")
    
    // 访问主机能力
    val host = context.host
    context.log("Platform: ${host.platform.name}")
}
```

#### onEnable

```kotlin
fun onEnable()
```

插件启用时调用。

**调用时机：** 用户在设置中启用插件时调用。

#### onDisable

```kotlin
fun onDisable()
```

插件禁用时调用。

**调用时机：** 用户在设置中禁用插件时调用。

#### onUnload

```kotlin
fun onUnload()
```

插件卸载时调用。

**调用时机：** 插件被删除或应用关闭时调用。

---

## PluginManifest

插件元数据清单，描述插件的基本信息。

```kotlin
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val platform: PluginPlatform = PluginPlatform.BOTH,
    val minApiVersion: String,
    val mainClass: String
)
```

### 属性

| 属性 | 类型 | 必需 | 默认值 | 说明 |
|------|------|------|--------|------|
| `id` | `String` | 是 | - | 插件唯一标识符，反向域名格式 |
| `name` | `String` | 是 | - | 插件显示名称 |
| `version` | `String` | 是 | - | 版本号，遵循语义化版本规范 |
| `author` | `String` | 是 | - | 作者名称 |
| `description` | `String` | 否 | `""` | 插件描述 |
| `tags` | `List<String>` | 否 | `emptyList()` | 标签列表 |
| `platform` | `PluginPlatform` | 否 | `BOTH` | 支持的平台 |
| `minApiVersion` | `String` | 是 | - | 最低 API 版本要求 |
| `mainClass` | `String` | 是 | - | 主类全限定名 |

---

## PluginContext

插件运行上下文，提供插件运行环境访问能力。

```kotlin
interface PluginContext {
    val pluginId: String
    val pluginDataDir: String
    val localization: PluginLocalization
    val appLocalization: PluginLocalization
    val host: PluginHost
    
    // 数据存储
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, defaultValue: Float): Float
    fun putFloat(key: String, value: Float)
    
    // 日志
    fun log(message: String)
    fun logError(message: String, throwable: Throwable? = null)
}
```

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `pluginId` | `String` | 插件唯一标识符 |
| `pluginDataDir` | `String` | 插件专属数据目录路径 |
| `localization` | `PluginLocalization` | 插件本地化接口 |
| `appLocalization` | `PluginLocalization` | 应用全局本地化接口 |
| `host` | `PluginHost` | 主机应用访问接口 |

---

## PluginHost

提供对主机应用的访问能力，允许插件与主机交互。

```kotlin
interface PluginHost {
    // 状态流
    val streamState: StateFlow<StreamState>
    val audioLevels: StateFlow<Float>
    val isMuted: StateFlow<Boolean>
    val connectionInfo: StateFlow<ConnectionInfo?>
    val audioConfig: StateFlow<AudioConfig>
    
    // 音频配置
    fun updateAudioConfig(config: AudioConfig)
    fun updateAudioConfig(block: AudioConfig.() -> AudioConfig)
    
    // 流控制
    suspend fun startStream(ip: String, port: Int, mode: ConnectionMode, isClient: Boolean)
    suspend fun stopStream()
    suspend fun setMute(muted: Boolean)
    fun setMonitoring(enabled: Boolean)
    
    // 音频效果注册
    fun registerAudioEffect(effect: AudioEffectProvider, priority: Int = 100)
    fun unregisterAudioEffect(effect: AudioEffectProvider)
    
    // 数据通道
    fun createDataChannel(id: String, config: DataChannelConfig = DataChannelConfig()): PluginDataChannel
    fun getDataChannel(id: String): PluginDataChannel?
    fun closeDataChannel(id: String)
    
    // UI 反馈
    fun showSnackbar(message: String)
    fun showNotification(title: String, message: String)
    
    // 设置访问
    fun getSetting(key: String, defaultValue: String): String
    fun setSetting(key: String, value: String)
    fun getSettingBoolean(key: String, defaultValue: Boolean): Boolean
    fun setSettingBoolean(key: String, value: Boolean)
    fun getSettingInt(key: String, defaultValue: Int): Int
    fun setSettingInt(key: String, value: Int)
    fun getSettingFloat(key: String, defaultValue: Float): Float
    fun setSettingFloat(key: String, value: Float)
    
    // 平台信息
    val platform: PlatformInfo
}
```

### 状态流

| 属性 | 类型 | 说明 |
|------|------|------|
| `streamState` | `StateFlow<StreamState>` | 当前流状态 |
| `audioLevels` | `StateFlow<Float>` | 音频电平 |
| `isMuted` | `StateFlow<Boolean>` | 静音状态 |
| `connectionInfo` | `StateFlow<ConnectionInfo?>` | 连接信息 |
| `audioConfig` | `StateFlow<AudioConfig>` | 音频配置 |

### 方法

#### updateAudioConfig

```kotlin
fun updateAudioConfig(config: AudioConfig)
fun updateAudioConfig(block: AudioConfig.() -> AudioConfig)
```

更新音频处理配置。

**示例：**
```kotlin
context.host.updateAudioConfig { 
    copy(enableNS = true, nsType = NoiseReductionType.RNNoise)
}
```

#### startStream / stopStream

```kotlin
suspend fun startStream(ip: String, port: Int, mode: ConnectionMode, isClient: Boolean)
suspend fun stopStream()
```

启动/停止音频流。

#### registerAudioEffect / unregisterAudioEffect

```kotlin
fun registerAudioEffect(effect: AudioEffectProvider, priority: Int = 100)
fun unregisterAudioEffect(effect: AudioEffectProvider)
```

注册/注销自定义音频效果器。优先级数值越小，越先执行。

#### createDataChannel / getDataChannel / closeDataChannel

```kotlin
fun createDataChannel(id: String, config: DataChannelConfig = DataChannelConfig()): PluginDataChannel
fun getDataChannel(id: String): PluginDataChannel?
fun closeDataChannel(id: String)
```

创建/获取/关闭自定义网络数据通道。

**参数：**
- `id` - 通道唯一标识符
- `config` - 通道配置

**示例：**
```kotlin
val channel = context.host.createDataChannel("my-channel")
channel.connect("192.168.1.100", 8080)
// ... 使用通道
context.host.closeDataChannel("my-channel")
```

#### showSnackbar / showNotification

```kotlin
fun showSnackbar(message: String)
fun showNotification(title: String, message: String)
```

显示 UI 反馈。

---

## PluginDataChannel

自定义网络数据通道接口，允许插件建立独立的网络连接。

```kotlin
enum class DataChannelMode {
    Tcp, Udp
}

data class DataChannelConfig(
    val mode: DataChannelMode = DataChannelMode.Tcp,
    val port: Int = 0,
    val bufferSize: Int = 8192
)

interface PluginDataChannel {
    val id: String
    val config: DataChannelConfig
    val isConnected: Flow<Boolean>
    val localPort: Int
    
    suspend fun connect(host: String, port: Int): Result<Unit>
    suspend fun bind(port: Int = 0): Result<Unit>
    suspend fun send(data: ByteArray): Result<Unit>
    fun receive(): Flow<ByteArray>
    suspend fun close()
}
```

### DataChannelMode

| 模式 | 说明 |
|------|------|
| `Tcp` | TCP 连接，可靠传输 |
| `Udp` | UDP 连接，低延迟 |

### DataChannelConfig

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `mode` | `DataChannelMode` | `Tcp` | 连接模式 |
| `port` | `Int` | `0` | 本地端口（0 表示自动分配） |
| `bufferSize` | `Int` | `8192` | 接收缓冲区大小 |

### PluginDataChannel 方法

#### connect

```kotlin
suspend fun connect(host: String, port: Int): Result<Unit>
```

作为客户端连接到远程主机。

**参数：**
- `host` - 远程主机地址
- `port` - 远程端口

**返回：** `Result<Unit>` 表示连接结果

#### bind

```kotlin
suspend fun bind(port: Int = 0): Result<Unit>
```

绑定本地端口，作为服务端监听连接。

**参数：**
- `port` - 本地端口，0 表示自动分配

**返回：** `Result<Unit>` 表示绑定结果

#### send

```kotlin
suspend fun send(data: ByteArray): Result<Unit>
```

发送数据。

#### receive

```kotlin
fun receive(): Flow<ByteArray>
```

接收数据流。

#### close

```kotlin
suspend fun close()
```

关闭通道。

### 使用示例

```kotlin
class CameraStreamPlugin : Plugin {
    private lateinit var channel: PluginDataChannel
    
    override fun onEnable() {
        channel = context.host.createDataChannel("camera-stream", 
            DataChannelConfig(mode = DataChannelMode.Udp)
        )
        
        scope.launch {
            channel.connect("192.168.1.100", 9000)
                .onSuccess {
                    while (true) {
                        val frame = captureCameraFrame()
                        channel.send(frame)
                        delay(33) // ~30fps
                    }
                }
                .onFailure { e ->
                    context.logError("Connection failed: ${e.message}")
                }
        }
    }
    
    override fun onDisable() {
        context.host.closeDataChannel("camera-stream")
    }
}
```

---

## PluginInfo

插件运行时信息。

```kotlin
data class PluginInfo(
    val manifest: PluginManifest,
    val isEnabled: Boolean = false,
    val isLoaded: Boolean = false,
    val installPath: String,
    val iconPath: String? = null
)
```

---

## PluginPlatform

插件支持平台枚举。

```kotlin
enum class PluginPlatform {
    MOBILE,    // 仅移动端
    DESKTOP,   // 仅桌面端
    BOTH       // 两端都需要安装
}
```

---

## PluginUIProvider

插件 UI 提供者接口。

```kotlin
enum class MobileUIMode {
    Dialog,    // 对话框模式
    NewScreen  // 新页面模式
}

interface PluginUIProvider {
    val hasMainWindow: Boolean get() = false
    val hasDialog: Boolean get() = false
    
    // 窗口配置（桌面端）
    val windowWidth: Dp get() = 600.dp
    val windowHeight: Dp get() = 500.dp
    val windowTitle: String get() = "Plugin Window"
    val windowResizable: Boolean get() = true
    
    // 移动端 UI 模式
    val mobileUIMode: MobileUIMode get() = MobileUIMode.Dialog
    
    @Composable
    fun MainWindow(onClose: () -> Unit) {}
    
    @Composable
    fun DialogContent(onDismiss: () -> Unit) {}
}
```

### 属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `hasMainWindow` | `Boolean` | `false` | 是否提供主窗口 |
| `hasDialog` | `Boolean` | `false` | 是否提供对话框 |
| `windowWidth` | `Dp` | `600.dp` | 窗口宽度（桌面端） |
| `windowHeight` | `Dp` | `500.dp` | 窗口高度（桌面端） |
| `windowTitle` | `String` | `"Plugin Window"` | 窗口标题 |
| `windowResizable` | `Boolean` | `true` | 窗口是否可调整大小 |
| `mobileUIMode` | `MobileUIMode` | `Dialog` | 移动端 UI 模式 |

---

## PluginSettingsProvider

插件设置页面提供者接口。

```kotlin
interface PluginSettingsProvider {
    @Composable
    fun SettingsContent()
}
```

---

## AudioEffectProvider

音频效果提供者接口，用于实现自定义音频处理。

```kotlin
interface AudioEffectProvider {
    val id: String
    val name: String
    val description: String
    var isEnabled: Boolean
    
    fun process(input: ShortArray, channelCount: Int, sampleRate: Int): ShortArray
    fun reset()
    fun release()
    fun onConfigChanged(config: AudioConfig) {}
}
```

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 效果器唯一标识符 |
| `name` | `String` | 效果器显示名称 |
| `description` | `String` | 效果器描述 |
| `isEnabled` | `Boolean` | 是否启用 |

### 方法

#### process

```kotlin
fun process(input: ShortArray, channelCount: Int, sampleRate: Int): ShortArray
```

处理音频数据。

**参数：**
- `input` - 输入音频采样数据（16-bit PCM）
- `channelCount` - 声道数
- `sampleRate` - 采样率

**返回：** 处理后的音频数据

---

## AudioEffectPlugin

音频效果插件接口，简化音频效果插件的实现。

```kotlin
interface AudioEffectPlugin : Plugin {
    val audioEffectProvider: AudioEffectProvider
    val effectPriority: Int get() = 100
}
```

---

## 数据类型

### StreamState

```kotlin
enum class StreamState {
    Idle, Connecting, Streaming, Error
}
```

### ConnectionMode

```kotlin
enum class ConnectionMode {
    Wifi, Usb
}
```

### NoiseReductionType

```kotlin
enum class NoiseReductionType {
    Ulunas, RNNoise, Speexdsp, None
}
```

### AudioConfig

```kotlin
data class AudioConfig(
    val enableNS: Boolean = false,
    val nsType: NoiseReductionType = NoiseReductionType.RNNoise,
    val enableAGC: Boolean = false,
    val agcTargetLevel: Int = 32000,
    val enableVAD: Boolean = false,
    val vadThreshold: Int = 10,
    val enableDereverb: Boolean = false,
    val dereverbLevel: Float = 0.5f,
    val amplification: Float = 0.0f
)
```

### ConnectionInfo

```kotlin
data class ConnectionInfo(
    val mode: ConnectionMode,
    val ipAddress: String,
    val port: Int,
    val isClient: Boolean
)
```

### PlatformInfo

```kotlin
data class PlatformInfo(
    val name: String,
    val version: String,
    val isDesktop: Boolean,
    val isMobile: Boolean
)
```

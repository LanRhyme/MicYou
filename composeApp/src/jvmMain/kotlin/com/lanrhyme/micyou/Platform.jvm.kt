package com.lanrhyme.micyou

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.platform.FirewallManager
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.platform.WindowsAccentColorExtractor
import com.lanrhyme.micyou.theme.PaletteStyle
import com.lanrhyme.micyou.theme.dynamicColorScheme
import com.lanrhyme.micyou.network.LocalNetworkAddressProvider
import com.lanrhyme.micyou.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.autoSourceAuto

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val type: PlatformType = PlatformType.Desktop
    override val ipAddress: String
        get() = LocalNetworkAddressProvider.getPreferredIpAddress()

    override val ipAddresses: List<String>
        get() = LocalNetworkAddressProvider.getCachedIpAddressDetails().map { it.ip }

    override val ipAddressDetails: List<IpAddressInfo>
        get() = LocalNetworkAddressProvider.getCachedIpAddressDetails()
}

actual fun getPlatform(): Platform = JVMPlatform()
actual suspend fun getPreferredLocalIpAddress(): String = withContext(Dispatchers.IO) {
    LocalNetworkAddressProvider.refreshNow().firstOrNull()?.ip ?: "Unknown"
}
actual suspend fun refreshLocalIpAddressDetails(): List<IpAddressInfo> = withContext(Dispatchers.IO) {
    LocalNetworkAddressProvider.refreshNow()
}

actual fun getAppVersion(): String {
    val fromManifest = object {}.javaClass.`package`?.implementationVersion
    if (!fromManifest.isNullOrBlank()) return fromManifest
    val fromProperty = System.getProperty("app.version")
    if (!fromProperty.isNullOrBlank()) return fromProperty
    return "dev"
}

actual fun openUrl(url: String) {
    try {
        when {
            PlatformInfo.isWindows -> Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler $url")
            PlatformInfo.isMacOS -> Runtime.getRuntime().exec("/usr/bin/open $url")
            PlatformInfo.isLinux -> Runtime.getRuntime().exec("xdg-open $url")
            else -> java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    } catch (e: Exception) {
        Logger.e("Platform", "Failed to open URL: $url", e)
    }
}

actual fun copyToClipboard(text: String) {
    try {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        val stringSelection = java.awt.datatransfer.StringSelection(text)
        clipboard.setContents(stringSelection, null)
        Logger.d("Platform", "Copied to clipboard: ${text.take(50)}...")
    } catch (e: Exception) {
        Logger.e("Platform", "Failed to copy to clipboard", e)
    }
}

actual suspend fun isPortAllowed(port: Int, protocol: String): Boolean =
    withContext(Dispatchers.IO) {
        val fwProtocol = runCatching { FirewallManager.Protocol.valueOf(protocol.uppercase()) }.getOrDefault(FirewallManager.Protocol.TCP)
        FirewallManager.isPortAllowed(port, fwProtocol)
    }

actual suspend fun addFirewallRule(port: Int, protocol: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        val fwProtocol = runCatching { FirewallManager.Protocol.valueOf(protocol.uppercase()) }.getOrDefault(FirewallManager.Protocol.TCP)
        if (FirewallManager.addFirewallRule(port, fwProtocol)) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to add firewall rule"))
        }
    }

actual fun isDynamicColorSupported(): Boolean {
    // 目前只为 Windows 提供莫奈取色
    return PlatformInfo.isWindows
}

actual fun getDynamicSeedColor(): Long? {
    // 目前只为 Windows 提供动态种子色
    if (!PlatformInfo.isWindows) {
        return null
    }
    return WindowsAccentColorExtractor.getAccentColor()?.value?.toLong()
}

@Composable
actual fun getDynamicColorScheme(isDark: Boolean, paletteStyle: PaletteStyle): ColorScheme? {
    // 目前只为 Windows 提供莫奈取色
    if (!PlatformInfo.isWindows) {
        return null
    }

    // 使用 remember 缓存生成的配色方案，避免每次重组时重复获取系统颜色
    return remember(isDark, paletteStyle) {
        // 获取 Windows 系统主题色
        val seedColor = WindowsAccentColorExtractor.getAccentColor()

        // 如果无法获取系统主题色，返回 null 使用默认主题
        val color = seedColor ?: return@remember null

        Logger.d("Platform", "Using Windows accent color: $color")

        // 使用用户选择的 paletteStyle 生成配色方案
        dynamicColorScheme(color, isDark, paletteStyle)
    }
}

actual fun getAudioSourceOptions(): List<AudioSourceOption> {
    val mixers = AudioSystem.getMixerInfo()
    val options = mutableListOf<AudioSourceOption>()
    
    // 添加 "Auto (Recommended)" 选项
    options.add(AudioSourceOption(name = "Auto", labelRes = Res.string.autoSourceAuto))
    
    mixers.forEach { mixerInfo ->
        try {
            val mixer = AudioSystem.getMixer(mixerInfo)
            // 过滤出支持输出 (SourceDataLine) 的混音器
            if (mixer.sourceLineInfo.any { it is javax.sound.sampled.DataLine.Info && it.lineClass == SourceDataLine::class.java }) {
                // 排除一些显然不是物理/虚拟播放设备的混音器（可选）
                val name = mixerInfo.name
                if (!options.any { it.name == name }) {
                    options.add(AudioSourceOption(name = name, label = name))
                }
            }
        } catch (e: Exception) {
            // 忽略有问题的混音器
        }
    }
    
    return options
}

actual fun isVirtualDeviceInstalled(): Boolean = VirtualAudioDeviceManager.isVirtualDeviceInstalled()

actual suspend fun installVBCable() = VirtualAudioDeviceManager.installVirtualDevice()

actual fun getVBCableInstallProgress(): kotlinx.coroutines.flow.Flow<String?> = VirtualAudioDeviceManager.installProgress

actual fun isWindowsPlatform(): Boolean = PlatformInfo.isWindows

actual fun isMacOSPlatform(): Boolean = PlatformInfo.isMacOS

private fun findLocalProperty(key: String): String {
    // Try system property first
    val fromProp = System.getProperty(key.lowercase().replace('_', '.'))
    if (!fromProp.isNullOrBlank()) return fromProp
    // Search for local.properties in current dir and parents
    var dir = java.io.File(System.getProperty("user.dir"))
    while (dir != null) {
        val file = java.io.File(dir, "local.properties")
        if (file.exists()) {
            val props = java.util.Properties()
            file.inputStream().use { props.load(it) }
            val value = props.getProperty(key, "")
            if (value.isNotBlank()) return value
        }
        dir = dir.parentFile
    }
    return ""
}

actual fun getAifadianApiToken(): String = findLocalProperty("AIFADIAN_API_TOKEN")

actual fun getAifadianUserId(): String = findLocalProperty("AIFADIAN_USER_ID")

actual fun md5(input: String): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

actual fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000

@Composable
actual fun QrCodeImage(content: String, modifier: Modifier, sizeDp: Int) {
    val qrBitmap = remember(content) {
        runCatching { QrCodeGenerator.generateQrCodeImageBitmap(content) }.getOrNull()
    }
    qrBitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "QR Code",
            modifier = modifier.size(sizeDp.dp)
        )
    }
}

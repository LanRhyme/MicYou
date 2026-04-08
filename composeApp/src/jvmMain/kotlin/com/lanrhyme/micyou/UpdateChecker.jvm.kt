package com.lanrhyme.micyou

import com.lanrhyme.micyou.platform.PlatformInfo
import java.io.File

actual suspend fun writeToFile(path: String, writer: suspend ((ByteArray, Int, Int) -> Unit) -> Unit) {
    File(path).apply { parentFile?.mkdirs() }.outputStream().use { fos -> writer(fos::write) }
}

actual fun findPlatformAsset(assets: List<GitHubAsset>): GitHubAsset? = when {
    PlatformInfo.isWindows -> assets.find { it.name.contains("Win") && it.name.endsWith("-installer.exe") } ?: assets.find { it.name.contains("Win") && it.name.endsWith(".zip") }
    PlatformInfo.isMacOS -> assets.find { it.name.contains("macOS") && it.name.endsWith(".dmg") && (!PlatformInfo.isArm64 || it.name.contains("arm64")) } ?: assets.find { it.name.contains("macOS") && it.name.endsWith(".dmg") }
    PlatformInfo.isLinux -> assets.find { it.name.contains("Linux") && it.name.endsWith(".deb") } ?: assets.find { it.name.contains("Linux") && it.name.endsWith(".rpm") }
    else -> null
}

actual fun getUpdateDownloadPath(fileName: String) = File(System.getProperty("java.io.tmpdir"), "MicYou-update${File.separator}$fileName").absolutePath

actual fun installUpdate(filePath: String) {
    if (!File(filePath).exists()) return Logger.e("UpdateInstaller", "Update file not found: $filePath")
    try {
        when {
            PlatformInfo.isWindows && filePath.endsWith(".exe") -> ProcessBuilder(filePath)
            PlatformInfo.isMacOS && filePath.endsWith(".dmg") -> ProcessBuilder("open", filePath)
            PlatformInfo.isLinux && (filePath.endsWith(".deb") || filePath.endsWith(".rpm")) -> {
                if (runCatching { ProcessBuilder("xdg-open", filePath).start() }.isSuccess) null
                else ProcessBuilder("pkexec", if (filePath.endsWith(".deb")) "dpkg" else "rpm", "-i", filePath)
            }
            else -> return openUrl(filePath).also { Logger.w("UpdateInstaller", "Opened with default: $filePath") }
        }?.start()
        
        Logger.i("UpdateInstaller", "Installer launched. Delaying 1.5s for safe handoff...")
        Thread.sleep(1500)
        System.exit(0)
    } catch (e: Exception) { Logger.e("UpdateInstaller", "Failed to install update", e) }
}

actual fun getMirrorOs() = when { PlatformInfo.isWindows -> "windows"; PlatformInfo.isMacOS -> "darwin"; PlatformInfo.isLinux -> "linux"; else -> "" }
actual fun getMirrorArch() = if (PlatformInfo.isArm64) "arm64" else "amd64"
actual fun getPlatformName() = when { PlatformInfo.isWindows -> "Windows"; PlatformInfo.isMacOS -> "macOS"; PlatformInfo.isLinux -> "Linux"; else -> "Unknown" }

actual fun isPortableApp(): Boolean {
    val appBaseDir = File(System.getProperty("compose.application.resources.dir", System.getProperty("user.dir"))).parentFile?.parentFile ?: File(System.getProperty("user.dir"))
    return when {
        PlatformInfo.isWindows -> !File(appBaseDir, "Uninstall.exe").exists() && !File(System.getProperty("user.dir"), "Uninstall.exe").exists()
        PlatformInfo.isLinux -> !appBaseDir.absolutePath.startsWith("/opt") && !appBaseDir.absolutePath.startsWith("/usr")
        PlatformInfo.isMacOS -> !appBaseDir.absolutePath.startsWith("/Applications")
        else -> false
    }
}

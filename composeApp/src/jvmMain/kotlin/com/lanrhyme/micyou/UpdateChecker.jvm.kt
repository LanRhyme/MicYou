package com.lanrhyme.micyou

import com.lanrhyme.micyou.platform.PlatformInfo
import java.io.File
import java.io.FileOutputStream

actual suspend fun writeToFile(path: String, writer: suspend ((ByteArray, Int, Int) -> Unit) -> Unit) {
    val file = File(path)
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { fos ->
        writer { buffer, offset, length ->
            fos.write(buffer, offset, length)
        }
    }
}

actual fun findPlatformAsset(assets: List<GitHubAsset>): GitHubAsset? {
    return when {
        PlatformInfo.isWindows -> assets.find { it.name.contains("Win") && it.name.endsWith("-installer.exe") }
            ?: assets.find { it.name.contains("Win") && it.name.endsWith(".zip") }
        PlatformInfo.isMacOS -> {
            val archSuffix = if (PlatformInfo.isArm64) "arm64" else "x64"
            assets.find { it.name.contains("macOS") && it.name.contains(archSuffix) && it.name.endsWith(".dmg") }
                ?: assets.find { it.name.contains("macOS") && it.name.endsWith(".dmg") }
        }
        PlatformInfo.isLinux -> assets.find { it.name.contains("Linux") && it.name.endsWith(".deb") }
            ?: assets.find { it.name.contains("Linux") && it.name.endsWith(".rpm") }
        else -> null
    }
}

actual fun getUpdateDownloadPath(fileName: String): String {
    val tempDir = System.getProperty("java.io.tmpdir")
    return File(tempDir, "MicYou-update${File.separator}$fileName").absolutePath
}

actual fun installUpdate(filePath: String) {
    if (!File(filePath).exists()) return Logger.e("UpdateInstaller", "Update file not found: $filePath")

    try {
        val launcher = when {
            PlatformInfo.isWindows && filePath.endsWith(".exe") -> ProcessBuilder(filePath)
            PlatformInfo.isMacOS && filePath.endsWith(".dmg") -> ProcessBuilder("open", filePath)
            PlatformInfo.isLinux && filePath.endsWith(".deb") -> {
                if (runCatching { ProcessBuilder("xdg-open", filePath).start() }.isSuccess) null else ProcessBuilder("pkexec", "dpkg", "-i", filePath)
            }
            PlatformInfo.isLinux && filePath.endsWith(".rpm") -> {
                if (runCatching { ProcessBuilder("xdg-open", filePath).start() }.isSuccess) null else ProcessBuilder("pkexec", "rpm", "-i", filePath)
            }
            else -> {
                openUrl(filePath)
                return Logger.w("UpdateInstaller", "Unknown file type, opened with default: $filePath")
            }
        }
        
        Logger.i("UpdateInstaller", "Launching installer: $filePath")
        launcher?.start()
        
        Logger.i("UpdateInstaller", "Installer launched. Delaying 1.5s for safe handoff...")
        Thread.sleep(1500)
        System.exit(0)
    } catch (e: Exception) {
        Logger.e("UpdateInstaller", "Failed to install update", e)
    }
}

actual fun getMirrorOs(): String {
    return when {
        PlatformInfo.isWindows -> "windows"
        PlatformInfo.isMacOS -> "darwin"
        PlatformInfo.isLinux -> "linux"
        else -> ""
    }
}

actual fun getMirrorArch(): String {
    return when {
        PlatformInfo.isArm64 -> "arm64"
        else -> "amd64"
    }
}

actual fun getPlatformName(): String {
    return when {
        PlatformInfo.isWindows -> "Windows"
        PlatformInfo.isMacOS -> "macOS"
        PlatformInfo.isLinux -> "Linux"
        else -> "Unknown"
    }
}

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
    val file = File(filePath)
    if (!file.exists()) {
        Logger.e("UpdateInstaller", "Update file not found: $filePath")
        return
    }

    try {
        when {
            PlatformInfo.isWindows && filePath.endsWith(".exe") -> {
                Logger.i("UpdateInstaller", "Launching Windows installer: $filePath")
                ProcessBuilder(filePath).start()
            }
            PlatformInfo.isMacOS && filePath.endsWith(".dmg") -> {
                Logger.i("UpdateInstaller", "Opening macOS DMG: $filePath")
                ProcessBuilder("open", filePath).start()
            }
            PlatformInfo.isLinux && filePath.endsWith(".deb") -> {
                Logger.i("UpdateInstaller", "Opening Linux deb package: $filePath")
                // Try xdg-open first, fallback to dpkg
                try {
                    ProcessBuilder("xdg-open", filePath).start()
                } catch (e: Exception) {
                    ProcessBuilder("sudo", "dpkg", "-i", filePath).start()
                }
            }
            PlatformInfo.isLinux && filePath.endsWith(".rpm") -> {
                Logger.i("UpdateInstaller", "Opening Linux rpm package: $filePath")
                try {
                    ProcessBuilder("xdg-open", filePath).start()
                } catch (e: Exception) {
                    ProcessBuilder("sudo", "rpm", "-i", filePath).start()
                }
            }
            else -> {
                Logger.w("UpdateInstaller", "Unknown file type, opening with default handler: $filePath")
                openUrl(filePath)
            }
        }
    } catch (e: Exception) {
        Logger.e("UpdateInstaller", "Failed to install update", e)
    }
}

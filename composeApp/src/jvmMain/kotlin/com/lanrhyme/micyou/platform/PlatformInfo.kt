package com.lanrhyme.micyou.platform

object PlatformInfo {
    enum class OS {
        WINDOWS, LINUX, MACOS, OTHER
    }
    val currentOS: OS by lazy {
        val osName = System.getProperty("os.name", "").lowercase()
        when {
            osName.contains("win") -> OS.WINDOWS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OS.LINUX
            osName.contains("mac") -> OS.MACOS
            else -> OS.OTHER
        }
    }
    val isWindows: Boolean get() = currentOS == OS.WINDOWS
    val isLinux: Boolean get() = currentOS == OS.LINUX
    val isMacOS: Boolean get() = currentOS == OS.MACOS

    enum class Arch {
        X86_64, ARM64, X86, OTHER
    }
    val currentArch: Arch by lazy {
        val arch = System.getProperty("os.arch", "").lowercase()
        when {
            arch == "x86_64" || arch == "amd64" || arch == "x64" -> Arch.X86_64
            arch == "aarch64" || arch == "arm64" -> Arch.ARM64
            arch == "x86" || arch == "i386" || arch == "i486" || arch == "i586" || arch == "i686" -> Arch.X86
            else -> Arch.OTHER
        }
    }
    val isX64: Boolean get() = currentArch == Arch.X86_64
    val isX86: Boolean get() = currentArch == Arch.X86
    val isArm64: Boolean get() = currentArch == Arch.ARM64
    
    val osName: String get() = System.getProperty("os.name", "Unknown")
    val osVersion: String get() = System.getProperty("os.version", "Unknown")
    val osArch: String get() = System.getProperty("os.arch", "Unknown")

    /**
     * 应用数据目录，遵循各平台标准规范。
     *
     * Linux:   $XDG_DATA_HOME/micyou/  →  ~/.local/share/micyou/
     * Windows: %LOCALAPPDATA%/MicYou/
     * macOS:   ~/Library/Application Support/MicYou/
     */
    val appDataDir: java.io.File by lazy {
        val home = System.getProperty("user.home")
        val dir = when {
            isLinux -> {
                val xdgData = System.getenv("XDG_DATA_HOME")
                    ?: "$home/.local/share"
                java.io.File(xdgData, "micyou")
            }
            isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: "$home/AppData/Local"
                java.io.File(localAppData, "MicYou")
            }
            isMacOS -> java.io.File("$home/Library/Application Support", "MicYou")
            else -> java.io.File(home, ".micyou")
        }
        dir.apply { mkdirs() }
    }

    fun migrateLegacyDataDir(logger: (String) -> Unit = {}) {
        val home = System.getProperty("user.home")
        val legacyDir = java.io.File(home, ".micyou")
        if (!legacyDir.isDirectory) return

        val targetDir = appDataDir
        if (targetDir.absolutePath == legacyDir.absolutePath) return
        // 移除全局 isNotEmpty 检查，改为在循环中判断单个文件是否存在，以支持增量迁移和避免系统文件干扰
        logger("Migrating data from $legacyDir to $targetDir ...")
        var copied = 0
        legacyDir.listFiles()?.forEach { file ->
            val dest = java.io.File(targetDir, file.name)
            if (dest.exists()) return@forEach
            try {
                file.copyRecursively(dest, overwrite = false)
                copied++
                logger("  Migrated: ${file.name}")
            } catch (e: Exception) {
                logger("  Failed: ${file.name} — ${e.message}")
            }
        }
        logger("Migration complete: $copied items copied, old directory preserved at $legacyDir")
    }
}

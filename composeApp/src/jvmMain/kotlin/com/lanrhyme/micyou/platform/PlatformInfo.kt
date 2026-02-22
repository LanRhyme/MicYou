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
}

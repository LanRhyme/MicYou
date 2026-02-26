package com.lanrhyme.micyou

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AppLanguage(val label: String, val code: String) {
    System("System / 跟随系统", "system"),
    Chinese("简体中文", "zh"),
    ChineseTraditional("繁體中文", "zh-TW"),
    Cantonese("粤语", "zh-HK"),
    English("English", "en"),
    ChineseCat("中文（猫猫语）🐱", "cat"),
    ChineseHard("中国人（坚硬）", "zh_hard"),
}

@Serializable
data class AppStrings(
    val appName: String = "MicYou",
    val ipLabel: String = "IP: ",
    val portLabel: String = "Port",
    val targetIpLabel: String = "Target IP",
    val targetIpUsbLabel: String = "Target IP (127.0.0.1)",
    val bluetoothAddressLabel: String = "Bluetooth Device Address (MAC)",
    val connectionModeLabel: String = "Connection Mode",
    val modeWifi: String = "Wi-Fi",
    val modeBluetooth: String = "Bluetooth",
    val modeUsb: String = "USB",
    val statusLabel: String = "Status: ",
    val statusIdle: String = "Idle",
    val statusConnecting: String = "Connecting...",
    val statusStreaming: String = "Streaming",
    val statusError: String = "Error",
    val muteLabel: String = "Mute",
    val unmuteLabel: String = "Unmute",
    val micMuted: String = "Microphone Muted",
    val micNormal: String = "Microphone Normal",
    val settingsTitle: String = "Settings",
    val close: String = "Close",
    val minimize: String = "Minimize",
    val start: String = "Start",
    val stop: String = "Stop",
    val waitAdb: String = "Waiting for ADB connection...",
    val usbAdbReverseHint: String = "Run on PC:",
    val generalSection: String = "General",
    val appearanceSection: String = "Appearance",
    val audioSection: String = "Audio",
    val aboutSection: String = "About",
    val languageLabel: String = "Language",
    val themeLabel: String = "Theme Mode",
    val themeSystem: String = "System",
    val themeLight: String = "Light",
    val themeDark: String = "Dark",
    val autoStartLabel: String = "Auto Start",
    val monitoringLabel: String = "Monitoring (Desktop)",
    val sampleRateLabel: String = "Sample Rate",
    val channelCountLabel: String = "Channels",
    val audioFormatLabel: String = "Audio Format",
    val enableNsLabel: String = "Noise Suppression",
    val nsTypeLabel: String = "Algorithm",
    val nsAlgorithmHelpTitle: String = "Noise Reduction Algorithms",
    val nsAlgorithmCloseButton: String = "Got it",
    val nsAlgorithmRNNoiseTitle: String = "RNNoise",
    val nsAlgorithmRNNoiseDesc: String = "Deep learning-based noise reduction algorithm with the best results. Intelligently identifies and eliminates various background noises while maintaining voice clarity. Suitable for most scenarios.",
    val nsAlgorithmUlnasTitle: String = "Ulunas (ONNX)",
    val nsAlgorithmUlnasDesc: String = "ONNX Runtime-based noise reduction model providing good noise reduction effects. Better than RNNoise in some cases, but may not work on some devices or systems.",
    val nsAlgorithmSpeexdspTitle: String = "Speexdsp",
    val nsAlgorithmSpeexdspDesc: String = "Traditional digital signal processing algorithm, lightweight and requires no additional models. Basic noise reduction effect, suitable for low-performance devices or latency-sensitive scenarios.",
    val nsAlgorithmRecommended: String = "Recommended",
    val nsAlgorithmAlternative: String = "Alternative",
    val nsAlgorithmLightweight: String = "Lightweight",
    val enableAgcLabel: String = "AGC",
    val agcTargetLabel: String = "Target Level",
    val enableVadLabel: String = "VAD",
    val vadThresholdLabel: String = "Sensitivity",
    val audioConfigAppliedLabel: String = "Applied",
    val enableDereverbLabel: String = "Dereverb",
    val dereverbLevelLabel: String = "Level",
    val amplificationLabel: String = "Amplification",
    val openSourceLicense: String = "License",
    val viewLibraries: String = "View Open Source Libraries",
    val softwareIntro: String = "Introduction",
    val introText: String = "MicYou is an open source microphone tool that turns your Android device into a high-quality microphone for your computer. Based on AndroidMic, it supports Wi-Fi (TCP), Bluetooth, and USB connections, providing low-latency audio transmission.",
    val systemConfigTitle: String = "System Configuration",
    val enableStreamingNotificationLabel: String = "Streaming Notification (Android)",
    val clickToStart: String = "Click to Start",
    val autoStartDesc: String = "Start streaming automatically on app launch",
    val noGeneralSettings: String = "No general settings available",
    val themeColorLabel: String = "Theme Color",
    val amplificationMultiplierLabel: String = "Multiplier",
    val licensesTitle: String = "Open Source Libraries and Licenses",
    val basedOnAndroidMic: String = "MicYou is based on AndroidMic.",
    val developerLabel: String = "Developer",
    val githubRepoLabel: String = "GitHub Repository",
    val versionLabel: String = "Version",
    val useDynamicColorLabel: String = "Use System Dynamic Color",
    val androidAudioProcessingLabel: String = "Built-in Audio Processing",
    val androidAudioProcessingDesc: String = "Use hardware audio processing. May affect output quality.",
    val contributorsLabel: String = "Contributors",
    val contributorsDesc: String = "Thanks to everyone who contributed to this project.",
    val autoConfigLabel: String = "Auto Configure Audio",
    val autoConfigDesc: String = "Automatically select optimal audio settings based on connection mode",
    val logsSection: String = "Logs",
    val exportLog: String = "Export Log",
    val exportLogDesc: String = "Export application logs for debugging",
    val logExported: String = "Log exported to: %s",
    val logExportFailed: String = "Failed to export log",
    val firewallTitle: String = "Firewall Check",
    val firewallMessage: String = "Port %d is not allowed by Windows Firewall. This may prevent Android devices from connecting via Wi-Fi.\n\nWould you like to try adding a firewall rule for this port? (Requires Administrator privileges)",
    val firewallConfirm: String = "Try Add Rule",
    val firewallDismiss: String = "Ignore",
    val trayShow: String = "Show App",
    val trayHide: String = "Hide App",
    val trayExit: String = "Exit",
    val minimizeToTrayLabel: String = "Minimize to Tray on Close",
    val closeConfirmTitle: String = "Close Confirmation",
    val closeConfirmMessage: String = "What would you like to do when closing the application?",
    val closeConfirmMinimize: String = "Minimize to Tray",
    val closeConfirmExit: String = "Exit Application",
    val closeConfirmRemember: String = "Don't ask again",
    val closeConfirmCancel: String = "Cancel",
    val closeActionLabel: String = "Close Button Action",
    val closeActionPrompt: String = "Ask every time",
    val closeActionMinimize: String = "Minimize to Tray",
    val closeActionExit: String = "Exit Application",

    // Update
    val updateTitle: String = "New Version Available",
    val updateMessage: String = "A new version (%s) is available on GitHub. Would you like to update now?",
    val updateNow: String = "Update Now",
    val updateLater: String = "Later",
    val checkUpdate: String = "Check for Updates",
    val isLatestVersion: String = "Already the latest version",
    val checkingUpdate: String = "Checking for updates...",
    val updateCheckFailed: String = "Failed to check for updates: %s",
    val newVersionReleased: String = "New version released",

    // BlackHole (macOS virtual audio)
    val blackHoleInstalled: String = "BlackHole is installed, please configure in System Settings",
    val blackHoleNotInstalled: String = "Please install BlackHole virtual audio driver manually",
    val blackHoleInstallHint: String = "Installation guide: existential.audio/blackhole/",
    val blackHoleConfigHint: String = "BlackHole installed, please configure in System Settings",
    val blackHoleNotFound: String = "Cannot find BlackHole virtual input device",
    val blackHoleSwitchSuccess: String = "Successfully switched to BlackHole",
    val blackHoleSwitchFailed: String = "Failed to switch to BlackHole",
    val blackHoleRestored: String = "Restored to original device",
    val blackHoleUsingDevice: String = "Using BlackHole device: %s",
    val blackHoleInitFailed: String = "Failed to initialize BlackHole",
    val blackHoleFallback: String = "BlackHole not found, falling back to default device",
    val blackHoleTrying: String = "macOS: Trying to use BlackHole virtual device",

    // Install progress
    val installOsNotSupported: String = "Auto-install not supported for current OS",
    val installCheckingPackage: String = "Checking installer...",
    val installDownloading: String = "Downloading VB-Cable driver...",
    val installDownloadFailed: String = "Download failed: cannot find or download driver",
    val installInstalling: String = "Installing VB-Cable driver...",
    val installConfiguring: String = "Configuring...",
    val installConfigComplete: String = "Configuration complete",
    val installNotCompleted: String = "Installation not completed or cancelled",
    val installError: String = "Installation error: %s",
    val installCheckingLinux: String = "Checking Linux audio system...",
    val installLinuxExists: String = "Virtual audio device exists, configuring...",
    val installCreatingDevice: String = "Creating virtual audio device...",
    val installDeviceCreated: String = "Virtual device created, configuring...",
    val installDeviceFailed: String = "Virtual device creation failed, check system permissions and audio service"
)

val LocalAppStrings = staticCompositionLocalOf { AppStrings() }

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private var cachedStrings: MutableMap<String, AppStrings> = mutableMapOf()

fun getStrings(language: AppLanguage): AppStrings {
    val langCode = when (language) {
        AppLanguage.Chinese -> "zh"
        AppLanguage.ChineseTraditional -> "zh-TW"
        AppLanguage.Cantonese -> "zh-HK"
        AppLanguage.English -> "en"
        AppLanguage.ChineseCat -> "cat"
        AppLanguage.ChineseHard -> "zh_hard"
        AppLanguage.System -> {
            val locale = Locale.current.toLanguageTag()
            when {
                locale.startsWith("zh-HK") -> "zh-HK"
                locale.startsWith("zh-TW") || locale.startsWith("zh-Hant") -> "zh-TW"
                locale.startsWith("zh") -> "zh"
                else -> "en"
            }
        }
    }
    
    return cachedStrings.getOrPut(langCode) {
        loadStringsFromResources(langCode)
    }
}

private fun loadStringsFromResources(langCode: String): AppStrings {
    return try {
        val fileName = when (langCode) {
            "zh-HK" -> "strings_zh_hk"
            "zh-TW" -> "strings_zh_tw"
            else -> "strings_$langCode"
        }
        val resourcePath = "i18n/$fileName.json"
        
        val jsonString = readResourceFile(resourcePath)
        if (jsonString != null) {
            json.decodeFromString<AppStrings>(jsonString)
        } else {
            AppStrings()
        }
    } catch (e: Exception) {
        Logger.e("Localization", "Failed to load strings for $langCode: ${e.message}")
        AppStrings()
    }
}

expect fun readResourceFile(path: String): String?

package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AppLanguage(val label: String, val code: String) {
    System("System", "system"),
    Chinese("简体中文", "zh"),
    ChineseTraditional("繁體中文", "zh-TW"),
    Cantonese("粤语", "zh-HK"),
    English("English", "en"),
    ChineseCat("中文（猫猫语）🐱", "cat"),
    ChineseHard("中国人（坚硬）", "zh_hard"),
}

@Serializable
data class AudioEnhancedStrings(
    // Performance Settings
    val performanceLabel: String = "Performance Mode",
    val performanceDefault: String = "Default",
    val performanceLowLatency: String = "Low Latency",
    val performanceHighQuality: String = "High Quality",
    val performanceInfoTitle: String = "Performance Mode Info",
    val performanceInfoDescription: String = "Adjust audio processing buffer settings for different scenarios:",
    val performanceDefaultDescription: String = "Balanced performance and stability, suitable for most scenarios with ~250ms buffer",
    val performanceLowLatencyDescription: String = "Smaller buffer (~100ms) for real-time communication, may cause audio glitches on unstable networks",
    val performanceHighQualityDescription: String = "Larger buffer (~500ms) for recording and high-fidelity transfer, higher latency but more stable",

    // Audio Metrics
    val bitrateLabel: String = "Bitrate",
    val latencyLabel: String = "Latency",
    val rmsLabel: String = "RMS",
    val peakLabel: String = "Peak"
)

@Serializable
data class ErrorStrings(
    // Error Dialog
    val errorDialogTitle: String = "Connection Error",
    val errorDialogDismiss: String = "Dismiss",
    val errorDialogRetry: String = "Retry",
    val errorDialogHelp: String = "View Help",
    val errorSuggestionsTitle: String = "Suggestions:",
    
    // Error Titles
    val errorNetworkTimeoutTitle: String = "Connection Timeout",
    val errorNetworkUnreachableTitle: String = "Network Unreachable",
    val errorPortInUseTitle: String = "Port Already in Use",
    val errorConnectionRefusedTitle: String = "Connection Refused",
    val errorFirewallBlockedTitle: String = "Firewall Blocked",
    val errorPermissionDeniedTitle: String = "Permission Denied",
    val errorAdminPrivilegeTitle: String = "Administrator Privilege Required",
    val errorDeviceNotFoundTitle: String = "Device Not Found",
    val errorBluetoothDisabledTitle: String = "Bluetooth Disabled",
    val errorBluetoothServiceUnavailableTitle: String = "Bluetooth Service Unavailable",
    val errorBluetoothAdapterNotFoundTitle: String = "Bluetooth Adapter Not Found",
    val errorBlueZNotInstalledTitle: String = "BlueZ Not Installed",
    val errorRfcommBindFailedTitle: String = "RFCOMM Bind Failed",
    val errorUsbConnectionFailedTitle: String = "USB Connection Failed",
    val errorAdbCommandFailedTitle: String = "ADB Command Failed",
    val errorHandshakeFailedTitle: String = "Handshake Failed",
    val errorProtocolErrorTitle: String = "Protocol Error",
    val errorVersionMismatchTitle: String = "Version Mismatch",
    val errorAudioDeviceTitle: String = "Audio Device Error",
    val errorAudioFormatTitle: String = "Audio Format Error",
    val errorUnknownTitle: String = "Unknown Error",
    
    // Error Messages
    val errorNetworkTimeoutMessage: String = "Connection timed out. Please check your network connection and target device status.",
    val errorNetworkUnreachableMessage: String = "Cannot reach IP address: %s. Please verify the IP address and ensure both devices are on the same network.",
    val errorPortInUseMessage: String = "Port %d is already in use by another application. Please choose a different port or close the conflicting application.",
    val errorConnectionRefusedMessage: String = "Connection was refused. The target device may not be running MicYou or is not listening on the specified port.",
    val errorConnectionRefusedWifiMessage: String = "Connection to %s was refused. Please ensure the desktop server is running and configured with the same port.",
    val errorFirewallBlockedMessage: String = "Port %d is blocked by Windows Firewall. Please add a firewall rule or run the application as administrator.",
    val errorPermissionDeniedMessage: String = "Insufficient permissions to perform this operation. Please run the application as administrator.",
    val errorAdminPrivilegeMessage: String = "This operation requires administrator privileges. Please restart the application as administrator.",
    val errorDeviceNotFoundMessage: String = "The specified Bluetooth device was not found. Please ensure the device is paired and Bluetooth is enabled.",
    val errorBluetoothDisabledMessage: String = "Bluetooth is disabled on this device. Please enable Bluetooth in system settings.",
    val errorBluetoothServiceUnavailableMessage: String = "Bluetooth service is not running. Please start the Bluetooth service (e.g., 'bluetoothctl' or systemd bluetooth service).",
    val errorBluetoothAdapterNotFoundMessage: String = "No Bluetooth adapter was found on this device. Please check if a Bluetooth hardware is present and properly connected.",
    val errorBlueZNotInstalledMessage: String = "BlueZ Bluetooth stack is not installed or not properly configured. BlueZ is required for Bluetooth functionality on Linux.",
    val errorRfcommBindFailedMessage: String = "Failed to bind RFCOMM device. The device may already be in use or requires elevated permissions.",
    val errorUsbConnectionFailedMessage: String = "USB connection failed. Please check your USB cable and ensure USB debugging is enabled on your Android device.",
    val errorAdbCommandFailedMessage: String = "ADB command execution failed. Please ensure ADB is installed and USB debugging is enabled.",
    val errorHandshakeFailedMessage: String = "Handshake failed with the target device. This may indicate a version mismatch or protocol error.",
    val errorProtocolErrorMessage: String = "A protocol error occurred during communication. Please restart both applications.",
    val errorVersionMismatchMessage: String = "Version mismatch detected. Please ensure both devices are running the same version of MicYou.",
    val errorAudioDeviceMessage: String = "Audio device error. Please check your audio device settings and restart the application.",
    val errorAudioFormatMessage: String = "Unsupported audio format. Please try different audio settings or use auto-configuration.",
    val errorUnknownMessage: String = "An unknown error occurred: %s",
    
    // Error Recovery Suggestions
    val errorSuggestionCheckNetwork: String = "• Check your Wi-Fi or network connection",
    val errorSuggestionCheckTargetRunning: String = "• Ensure the target device is running MicYou server",
    val errorSuggestionTryDifferentPort: String = "• Try using a different port number",
    val errorSuggestionChangePort: String = "• Change the port number in settings",
    val errorSuggestionCheckOtherApps: String = "• Check if other applications are using this port",
    val errorSuggestionCheckServerRunning: String = "• Verify the desktop server is running and ready",
    val errorSuggestionCheckServerConfig: String = "• Check server configuration (port, firewall settings)",
    val errorSuggestionCheckNetworkConnection: String = "• Verify network connection between devices",
    val errorSuggestionVerifyIpAddress: String = "• Double-check the IP address",
    val errorSuggestionCheckWifiConnected: String = "• Ensure both devices are connected to the same Wi-Fi network",
    val errorSuggestionAddFirewallRule: String = "• Allow MicYou through Windows Firewall",
    val errorSuggestionRunAsAdmin: String = "• Run the application as administrator",
    val errorSuggestionCheckAntivirus: String = "• Check antivirus or security software settings",
    val errorSuggestionEnableBluetooth: String = "• Enable Bluetooth in system settings",
    val errorSuggestionPairDevice: String = "• Pair the Bluetooth device first",
    val errorSuggestionStartBluetoothService: String = "• Start Bluetooth service: sudo systemctl start bluetooth",
    val errorSuggestionCheckBluetoothDaemon: String = "• Check Bluetooth daemon status: systemctl status bluetooth",
    val errorSuggestionCheckBluetoothHardware: String = "• Verify Bluetooth hardware is connected and recognized",
    val errorSuggestionCheckUsbBluetooth: String = "• If using USB Bluetooth adapter, check USB connection",
    val errorSuggestionInstallBlueZ: String = "• Install BlueZ: sudo apt install bluez (or equivalent for your distro)",
    val errorSuggestionCheckBlueZService: String = "• Ensure BlueZ service is running: sudo systemctl status bluetooth",
    val errorSuggestionReleaseRfcomm: String = "• Release existing RFCOMM binding: sudo rfcomm release 0",
    val errorSuggestionCheckUsbCable: String = "• Check USB cable connection",
    val errorSuggestionEnableUsbDebugging: String = "• Enable USB debugging in Android developer options",
    val errorSuggestionRunAdbCommand: String = "• Run this command on PC: %s",
    val errorSuggestionCheckAdbInstalled: String = "• Ensure ADB is installed on your computer",
    val errorSuggestionRunAdbManually: String = "• Manually run: %s",
    val errorSuggestionVersionMatch: String = "• Ensure both devices use the same MicYou version",
    val errorSuggestionRestartApp: String = "• Restart the application",
    val errorSuggestionCheckVersion: String = "• Check for updates on GitHub",
    val errorSuggestionCheckAudioDevice: String = "• Check audio device permissions and availability",
    val errorSuggestionChangeAudioConfig: String = "• Try different audio configuration settings",
    val errorSuggestionUseDefaultConfig: String = "• Use default or auto configuration",
    val errorSuggestionUpdateApp: String = "• Update to the latest version",
    val errorSuggestionCheckLogs: String = "• Check application logs for detailed error information"
)

@Serializable
data class UsbStrings(
    val usbModeDialogTitle: String = "USB Connection Setup",
    val usbModeDialogMessage: String = "USB connection requires ADB port forwarding. Please run the following command on your PC:",
    val usbModeDialogCommand: String = "adb reverse tcp:%d tcp:%d",
    val usbModeDialogNote: String = "Note: Make sure USB debugging is enabled on your Android device and ADB is installed on your PC.",
    val usbModeDialogCopy: String = "Copy Command",
    val usbModeDialogCopied: String = "Command copied!",
    val usbModeDialogDismiss: String = "Dismiss",
    val usbModeDialogHelp: String = "View Help",
    
    // USB Error Messages
    val usbNotConnectedError: String = "USB device not connected. Please connect your Android device via USB cable.",
    val usbAdbNotInstalledError: String = "ADB is not installed or not found in PATH. Please install Android SDK Platform Tools.",
    val usbPortForwardingFailed: String = "ADB port forwarding failed. Please check if USB debugging is enabled.",
    val usbConnectionTimeout: String = "USB connection timeout. Please verify the ADB command was executed successfully.",
    val usbPermissionDenied: String = "USB debugging permission denied. Please accept the debugging request on your Android device."
)

@Serializable
data class PermissionStrings(
    val permissionDialogTitle: String = "App Permissions",
    val permissionDialogMessage: String = "MicYou requires certain permissions to function properly. Please review and grant the following permissions:",
    val permissionRecordAudioLabel: String = "Microphone",
    val permissionRecordAudioDesc: String = "Required to capture and transmit audio from your device to the computer.",
    val permissionBluetoothConnectLabel: String = "Bluetooth Connect",
    val permissionBluetoothConnectDesc: String = "Used for Bluetooth audio streaming mode to connect with paired devices.",
    val permissionBluetoothScanLabel: String = "Bluetooth Scan",
    val permissionBluetoothScanDesc: String = "Used to discover nearby Bluetooth devices for connection.",
    val permissionPostNotificationsLabel: String = "Notifications",
    val permissionPostNotificationsDesc: String = "Displays streaming status notifications for better user experience.",
    val permissionRequired: String = "Required",
    val permissionOptional: String = "Optional",
    val permissionGranted: String = "Granted",
    val permissionClickToGrant: String = "Click to Grant",
    val permissionRequiredHint: String = "Required permissions must be granted to use the app.",
    val permissionRequestButton: String = "Grant Permissions",
    val permissionAllGranted: String = "All Set",
    val permissionManagementLabel: String = "Permission Management",
    val permissionAllGrantedStatus: String = "All permissions granted (%d/%d)",
    val permissionMissingWarning: String = "Some permissions missing (%d/%d granted)",
    val permissionChecking: String = "Checking permissions...",
    val permissionSettingsTitle: String = "App Permissions",
    val permissionOpenSettings: String = "Open System Settings",
    val permissionOpenSettingsDesc: String = "If you denied a permission, you need to grant it manually in system settings."
)

@Serializable
data class ExpressiveStrings(
    // Palette Style (Expressive 2025)
    val paletteStyleLabel: String = "Color Palette Style",
    val paletteStyleDesc: String = "Adjust color saturation and vibrancy",
    val paletteStyleTonal: String = "Tonal",
    val paletteStyleExpressive: String = "Expressive",
    val paletteStyleVibrant: String = "Vibrant",
    val paletteStyleMonochrome: String = "Monochrome",
    val paletteStyleRainbow: String = "Rainbow",

    // Expressive Style
    val expressiveStyleLabel: String = "Expressive Style",
    val useExpressiveShapesLabel: String = "Expressive Shapes",
    val useExpressiveShapesDesc: String = "Use larger rounded corners (Material 3 Expressive)",
    val useExpressiveTypographyLabel: String = "Expressive Typography",
    val useExpressiveTypographyDesc: String = "Use larger font sizes (Material 3 Expressive)"
)

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
    val pocketModeLabel: String = "Compact Mode",
    val pocketModeDesc: String = "Use a compact window layout",
    val monitoringLabel: String = "Monitoring",
    val sampleRateLabel: String = "Sample Rate",
    val channelCountLabel: String = "Channels",
    val audioFormatLabel: String = "Audio Format",
    val audioSourceLabel: String = "Audio Source",
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
    val enableDereverbLabel: String = "De-reverb",
    val dereverbLevelLabel: String = "Level",
    val amplificationLabel: String = "Amplification",
    val gainLabel: String = "Gain",
    val openSourceLicense: String = "License",
    val viewLibraries: String = "View Open Source Libraries",
    val softwareIntro: String = "Introduction",
    val introText: String = "MicYou is an open source microphone tool that turns your Android device into a high-quality microphone for your computer. Based on AndroidMic, it supports Wi-Fi (TCP), Bluetooth, and USB connections, providing low-latency audio transmission.",
    val systemConfigTitle: String = "System Configuration",
    val enableStreamingNotificationLabel: String = "Streaming Notification",
    val keepScreenOnLabel: String = "Keep Screen On",
    val keepScreenOnDesc: String = "Prevent the screen from turning off while using the app",
    val clickToStart: String = "Click to Start",
    val autoStartDesc: String = "Start streaming automatically on app launch",
    val noGeneralSettings: String = "No general settings available",
    val themeColorLabel: String = "Theme Color",
    val oledPureBlackLabel: String = "OLED Optimization",
    val oledPureBlackDesc: String = "Use a pure black background in dark mode",
    val amplificationMultiplierLabel: String = "Multiplier",
    val licensesTitle: String = "Open Source Libraries and Licenses",
    val basedOnAndroidMic: String = "MicYou is based on AndroidMic.",
    val developerLabel: String = "Developer",
    val githubRepoLabel: String = "GitHub Repository",
    val versionLabel: String = "Version",
    val useDynamicColorLabel: String = "Enable Dynamic Color",
    val useDynamicColorDesc: String = "Use system accent color for app theme",
    val dynamicColorActiveHint: String = "Currently using system dynamic color",
    val dynamicColorEnabledHint: String = "Dynamic color is enabled",
    val androidAudioProcessingLabel: String = "Built-in Audio Processing",
    val androidAudioProcessingDesc: String = "Use hardware audio processing. May affect output quality.",
    val contributorsLabel: String = "Contributors",
    val contributorsDesc: String = "Thanks to everyone who contributed to this project.",
    val contributorsLoading: String = "Loading contributors...",
    val contributorsPeopleCount: String = "%d contributors",
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
    val updateDownloading: String = "Downloading update...",
    val updateDownloadFailed: String = "Download failed: %s",
    val updateInstalling: String = "Installing update...",
    val updateGoToGitHub: String = "Go to GitHub",
    val autoCheckUpdateLabel: String = "Auto Check for Updates",
    val autoCheckUpdateDesc: String = "Automatically check for new versions on app launch",

    // MirrorChyan
    val mirrorCdkLabel: String = "MirrorChyan CDK",
    val mirrorCdkDesc: String = "Use MirrorChyan mirror for faster update downloads",
    val mirrorCdkPlaceholder: String = "Enter CDK...",
    val mirrorDownloadLabel: String = "Mirror Download",
    val mirrorDownloadDesc: String = "Use MirrorChyan mirror for faster downloads",
    val githubDownloadLabel: String = "GitHub Download",
    val mirrorCdkExpiredWarning: String = "CDK expiring soon",
    val mirrorCdkExpiredTime: String = "Expires: %s",
    val mirrorCdkGetLink: String = "No CDK? Get one from MirrorChyan",

    // First launch dialog
    val firstLaunchTitle: String = "Welcome to MicYou",
    val firstLaunchMessage: String = "You seem to be using MicYou for the first time. This app turns your Android device into a high-quality microphone for your computer via Wi-Fi, Bluetooth, or USB.",
    val firstLaunchGuideButton: String = "View Usage Guide",
    val firstLaunchGotItButton: String = "I know how to use it",
    val firstLaunchQuickStartTitle: String = "Quick Start Guide",
    val firstLaunchStep1Title: String = "1. Install Desktop App",
    val firstLaunchStep1Desc: String = "Download and install MicYou desktop app on your computer",
    val firstLaunchStep2Title: String = "2. Connect Devices",
    val firstLaunchStep2WifiDesc: String = "Wi-Fi: Enter computer IP address in the app",
    val firstLaunchStep2BluetoothDesc: String = "Bluetooth: Pair devices and select Bluetooth mode",
    val firstLaunchStep2UsbDesc: String = "USB: Connect via USB cable and run ADB command",
    val firstLaunchStep3Title: String = "3. Start Streaming",
    val firstLaunchStep3Desc: String = "Click Start button to begin audio streaming",
    val firstLaunchStep4Title: String = "4. Configure Audio",
    val firstLaunchStep4Desc: String = "Select VB-Cable as input device in your recording software",
    val firstLaunchVideoGuide: String = "Video Tutorial",
    val firstLaunchTextGuide: String = "Text Guide",

    // Plugins
    val pluginsSection: String = "Plugins",
    val importPlugin: String = "Import Plugin",
    val noPluginsInstalled: String = "No plugins installed",
    val deletePlugin: String = "Delete Plugin",
    val deletePluginConfirm: String = "Are you sure you want to delete \"%s\"?",
    val delete: String = "Delete",
    val cancel: String = "Cancel",
    val ok: String = "OK",
    val pluginPlatformWarningTitle: String = "Platform Incompatible",
    val pluginPlatformWarning: String = "Plugin \"%s\" is designed for %s platform and may not work correctly on your current device.",
    val pluginImportTitle: String = "Import Plugin",
    val pluginImportSelectFile: String = "Select Plugin File",
    val pluginImporting: String = "Importing plugin...",
    val pluginImportSuccess: String = "Plugin imported successfully",
    val pluginImportFailed: String = "Failed to import plugin: %s",
    val pluginEnabled: String = "Plugin enabled",
    val pluginDisabled: String = "Plugin disabled",
    val pluginSyncWarningTitle: String = "Plugin Sync Warning",
    val pluginSyncWarningMessage: String = "The following cross-platform plugins are not installed on both devices:",
    val missingOn: String = "Missing on",
    val pluginSyncDismiss: String = "Dismiss",
    val pluginOpenWindow: String = "Open Window",
    val pluginSettings: String = "Settings",

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
    val installDeviceFailed: String = "Virtual device creation failed, check system permissions and audio service",
    
    // Visualizer Settings
    val visualizerStyleLabel: String = "Visualizer Style",
    val visualizerStyleVolumeRing: String = "Volume Ring",
    val visualizerStyleRipple: String = "Ripple",
    val visualizerStyleBars: String = "Bars",
    val visualizerStyleWave: String = "Wave",
    val visualizerStyleGlow: String = "Glow",
    val visualizerStyleParticles: String = "Particles",
    
    // Background Settings
    val backgroundSettingsLabel: String = "Background",
    val selectBackgroundImage: String = "Select Image",
    val clearBackgroundImage: String = "Clear",
    val backgroundBrightnessLabel: String = "Brightness",
    val backgroundBlurLabel: String = "Blur",
    val cardOpacityLabel: String = "Card Opacity",
    val enableHazeEffectLabel: String = "Frosted Glass Effect",
    val enableHazeEffectDesc: String = "Add frosted glass blur effect to cards",
    
    // Floating Window
    val floatingWindowLabel: String = "Floating Window",
    val floatingWindowDesc: String = "Show a small always-on-top window with audio visualization",
    
    // System Title Bar
    val useSystemTitleBarLabel: String = "System Title Bar",
    val useSystemTitleBarDesc: String = "Use native system window decorations instead of custom title bar",

    // VB-Cable Detection Dialog
    val vbcableDetectTitle: String = "VB-Cable Driver Not Found",
    val vbcableDetectMessage: String = "VB-Cable virtual audio driver is not installed. This driver is required for microphone audio forwarding.",
    val vbcableAutoInstall: String = "Auto Install",
    val vbcableManualDownload: String = "Manual Download",
    val vbcableSkip: String = "Skip",
    val vbcableInstalled: String = "Installed",
    val vbcableNotInstalled: String = "Not Installed",
    val vbcableInstall: String = "Install",
    val vbcableInstalling: String = "Installing...",
    val vbcableSettingsLabel: String = "VB-Cable",

    // Error Strings (nested)
    val errors: ErrorStrings = ErrorStrings(),

    // Audio Enhanced Strings (nested)
    val audioEnhanced: AudioEnhancedStrings = AudioEnhancedStrings(),

    // USB Strings (nested)
    val usb: UsbStrings = UsbStrings(),

    // Permission Strings (nested)
    val permissions: PermissionStrings = PermissionStrings(),

    // Expressive Strings (nested)
    val expressive: ExpressiveStrings = ExpressiveStrings()
)

val LocalAppStrings = staticCompositionLocalOf { AppStrings() }
val LocalPermissionStrings = staticCompositionLocalOf { PermissionStrings() }

// Expect function for Android permission management UI
@Composable
expect fun AndroidPermissionManagementSection(cardOpacity: Float)

// Permission state class - defined in commonMain for cross-platform use
data class PermissionState(
    val type: PermissionType,
    val manifestPermission: String,
    val isGranted: Boolean,
    val minSdkVersion: Int = 0
)

// Permission type enum - defined in commonMain for cross-platform use
enum class PermissionType(val labelKey: String, val descKey: String, val isRequired: Boolean) {
    RECORD_AUDIO(
        labelKey = "permissionRecordAudioLabel",
        descKey = "permissionRecordAudioDesc",
        isRequired = true
    ),
    BLUETOOTH_CONNECT(
        labelKey = "permissionBluetoothConnectLabel",
        descKey = "permissionBluetoothConnectDesc",
        isRequired = false
    ),
    BLUETOOTH_SCAN(
        labelKey = "permissionBluetoothScanLabel",
        descKey = "permissionBluetoothScanDesc",
        isRequired = false
    ),
    POST_NOTIFICATIONS(
        labelKey = "permissionPostNotificationsLabel",
        descKey = "permissionPostNotificationsDesc",
        isRequired = false
    )
}

// Expect function to check if all required permissions are granted
expect fun hasAllRequiredPermissions(permissions: List<PermissionState>): Boolean

// Expect function for permission dialog - used in App.kt
@Composable
expect fun PermissionDialog(
    permissions: List<PermissionState>,
    onDismiss: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit
)

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

package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import micyou.composeapp.generated.resources.*

enum class AppLanguage(val label: String, val code: String) {
    System("System", "system"),
    Chinese("简体中文", "zh"),
    ChineseTraditional("繁體中文", "zh-TW"),
    Cantonese("粤语", "zh-HK"),
    English("English", "en"),
    ChineseCat("中文（猫猫语）🐱", "ca"),
    ChineseHard("中国人（坚硬）", "zh-rSS"),
}

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

@Composable
expect fun OpenPluginWindow(pluginId: String, viewModel: MainViewModel, onClose: () -> Unit)

@Composable
expect fun OpenPluginSettings(pluginId: String, viewModel: MainViewModel, onClose: () -> Unit)

expect fun setAppLocale(languageCode: String)

expect fun readResourceFile(path: String): String?

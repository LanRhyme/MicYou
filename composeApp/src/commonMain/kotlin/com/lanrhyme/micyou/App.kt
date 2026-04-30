package com.lanrhyme.micyou

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(
    viewModel: MainViewModel? = null,
    onMinimize: () -> Unit = {},
    onClose: () -> Unit = {},
    onExitApp: () -> Unit = {},
    onHideApp: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    isBluetoothDisabled: Boolean = false,
    // Permission dialog parameters (Android only)
    showPermissionDialog: Boolean = false,
    currentPermissions: List<PermissionState> = emptyList(),
    onRequestPermissions: (List<String>) -> Unit = {},
    onPermissionDialogDismiss: () -> Unit = {},
    // Flag to indicate permission dialog has been dismissed (to control first launch dialog timing)
    isPermissionDialogDismissed: Boolean = true
) {
    val platform = remember { getPlatform() }
    val isClient = platform.type == PlatformType.Android

    // Use passed viewModel or create one
    val finalViewModel = viewModel ?: if (isClient) viewModel { MainViewModel() } else remember { MainViewModel() }
    val uiState by finalViewModel.uiState.collectAsState()
    val languageCode = uiState.language.code

    // Set locale synchronously during composition, before any stringResource calls.
    // Locale.setDefault() is a simple field assignment on JVM — safe to call here.
    setAppLocale(languageCode)

    key(languageCode) {
        val seedColorObj = androidx.compose.ui.graphics.Color(uiState.seedColor.toInt())
        val updateInfo = uiState.updateInfo
        val pocketMode = uiState.pocketMode
        val useSystemTitleBar = uiState.useSystemTitleBar
        // Only show first launch dialog after permission dialog is dismissed
        val showFirstLaunchDialog = uiState.showFirstLaunchDialog && isPermissionDialogDismissed
        val showVBCableDialog = uiState.showVBCableDialog
        val vbcableInstallProgress = uiState.vbcableInstallProgress

        AppTheme(
            themeMode = uiState.themeMode,
            seedColor = seedColorObj,
            useDynamicColor = uiState.useDynamicColor,
            oledPureBlack = uiState.oledPureBlack,
            paletteStyle = uiState.paletteStyle,
            useExpressiveShapes = uiState.useExpressiveShapes
        ) {
            if (platform.type == PlatformType.Android) {
                MobileHome(finalViewModel)
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = if (useSystemTitleBar) RoundedCornerShape(0.dp) else RoundedCornerShape(22.dp)
                ) {
                    if (pocketMode) {
                        DesktopHome(
                            viewModel = finalViewModel,
                            onMinimize = onMinimize,
                            onClose = onClose,
                            onExitApp = onExitApp,
                            onHideApp = onHideApp,
                            onOpenSettings = onOpenSettings,
                            isBluetoothDisabled = isBluetoothDisabled
                        )
                    } else {
                        DesktopHomeEnhanced(
                            viewModel = finalViewModel,
                            onMinimize = onMinimize,
                            onClose = onClose,
                            onExitApp = onExitApp,
                            onHideApp = onHideApp,
                            onOpenSettings = onOpenSettings,
                            isBluetoothDisabled = isBluetoothDisabled
                        )
                    }
                }
            }

            // Update Dialog
            if (updateInfo != null) {
                val downloadState = uiState.updateDownloadState
                val downloadProgress = uiState.updateDownloadProgress
                val downloadedBytes = uiState.updateDownloadedBytes
                val totalBytes = uiState.updateTotalBytes
                val updateError = uiState.updateErrorMessage
                val useMirrorDownload = uiState.useMirrorDownload
                val isDownloading = downloadState == UpdateDownloadState.Downloading
                val isInstalling = downloadState == UpdateDownloadState.Installing
                val isFailed = downloadState == UpdateDownloadState.Failed

                AlertDialog(
                    onDismissRequest = {
                        if (!isDownloading && !isInstalling) {
                            finalViewModel.dismissUpdateDialog()
                        }
                    },
                    title = { Text(stringResource(Res.string.updateTitle)) },
                    text = {
                        Column {
                            if (isFailed) {
                                Text(stringResource(Res.string.updateDownloadFailed, updateError ?: ""))
                            } else if (isInstalling) {
                                Text(stringResource(Res.string.updateInstalling))
                            } else if (isDownloading) {
                                Text(stringResource(Res.string.updateDownloading))
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes),
                                    fontSize = 12.sp
                                )
                            } else {
                                Text(stringResource(Res.string.updateMessage, updateInfo.versionName))

                                // Mirror source follows settings automatically. Keep only the expiration hint.
                                if (useMirrorDownload && updateInfo.mirrorUrl != null) {
                                    updateInfo.cdkExpiredTime?.let { expiredTime ->
                                        val now = System.currentTimeMillis() / 1000
                                        val daysLeft = (expiredTime - now) / (24 * 60 * 60)
                                        if (daysLeft in 1..7) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                stringResource(Res.string.mirrorCdkExpiredWarning),
                                                color = MaterialTheme.colorScheme.error,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        if (isFailed) {
                            TextButton(onClick = {
                                finalViewModel.openGitHubRelease()
                            }) {
                                Text(stringResource(Res.string.updateGoToGitHub))
                            }
                        } else if (!isDownloading && !isInstalling) {
                            TextButton(onClick = {
                                finalViewModel.downloadAndInstallUpdate()
                            }) {
                                Text(stringResource(Res.string.updateNow))
                            }
                        }
                    },
                    dismissButton = {
                        if (!isDownloading && !isInstalling) {
                            TextButton(onClick = { finalViewModel.dismissUpdateDialog() }) {
                                Text(stringResource(Res.string.updateLater))
                            }
                        }
                    }
                )
            }

            // First Launch Dialog - Enhanced with Quick Start Guide
            if (showFirstLaunchDialog) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(stringResource(Res.string.firstLaunchTitle)) },
                    text = {
                        Column(
                            modifier = Modifier
                                .widthIn(min = 400.dp, max = 500.dp)
                                .heightIn(max = 450.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.firstLaunchMessage),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = stringResource(Res.string.firstLaunchQuickStartTitle),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // Step 1
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep1Title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep1Desc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // Step 2
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep2Title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep2WifiDesc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep2BluetoothDesc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep2UsbDesc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // Step 3
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep3Title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep3Desc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // Step 4
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep4Title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(Res.string.firstLaunchStep4Desc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            TextButton(onClick = {
                                openUrl("https://www.bilibili.com/video/BV1MpNKz8ELw")
                            }) {
                                Text(stringResource(Res.string.firstLaunchVideoGuide))
                            }
                            TextButton(onClick = {
                                openUrl("https://github.com/LanRhyme/MicYou/blob/master/docs/FAQ.md")
                            }) {
                                Text(stringResource(Res.string.firstLaunchTextGuide))
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            finalViewModel.dismissFirstLaunchDialog()
                        }) {
                            Text(stringResource(Res.string.firstLaunchGotItButton))
                        }
                    }
                )
            }

            // VB-Cable Detection Dialog
            if (showVBCableDialog) {
                AlertDialog(
                    onDismissRequest = { finalViewModel.setShowVBCableDialog(false) },
                    title = { Text(stringResource(Res.string.vbcableDetectTitle)) },
                    text = {
                        Column {
                            Text(stringResource(Res.string.vbcableDetectMessage))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            finalViewModel.setShowVBCableDialog(false)
                            finalViewModel.startVBCableInstallation()
                        }) {
                            Text(stringResource(Res.string.vbcableAutoInstall))
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                openUrl("https://vb-audio.com/Cable/")
                                finalViewModel.setShowVBCableDialog(false)
                            }) {
                                Text(stringResource(Res.string.vbcableManualDownload))
                            }
                            TextButton(onClick = {
                                finalViewModel.setShowVBCableDialog(false)
                            }) {
                                Text(stringResource(Res.string.vbcableSkip))
                            }
                        }
                    }
                )
            }

            // VB-Cable Installation Progress Dialog
            if (vbcableInstallProgress != null) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(stringResource(Res.string.installInstalling)) },
                    text = {
                        Column {
                            Text(vbcableInstallProgress)
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = { }
                )
            }

            // Permission Dialog (Android only)
            if (showPermissionDialog && platform.type == PlatformType.Android && currentPermissions.isNotEmpty()) {
                PermissionDialog(
                    permissions = currentPermissions,
                    onDismiss = onPermissionDialogDismiss,
                    onRequestPermissions = onRequestPermissions
                )
            }

            // Connection Error Dialog
            val errorDetailsValue = uiState.errorDetails
            if (uiState.showErrorDialog && errorDetailsValue != null) {
                ConnectionErrorDialog(
                    errorDetails = errorDetailsValue,
                    onDismiss = { finalViewModel.dismissErrorDialog() },
                    onRetry = { finalViewModel.retryAfterError() }
                )
            }
        }
    }
}

private fun parseLanguageCode(code: String): Pair<String, String> {
    return when (code) {
        "zh-TW" -> "zh" to "TW"
        "zh-HK" -> "zh" to "HK"
        "zh-rSS" -> "zh" to "SS"
        "system" -> "" to ""
        else -> code to ""
    }
}

/**
 * 连接错误对话框组件
 * 显示详细的错误信息和恢复建议
 */
@Composable
private fun ConnectionErrorDialog(
    errorDetails: ConnectionErrorDetails,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = errorDetails.localizedTitle,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 错误消息
                Text(
                    text = errorDetails.localizedMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 恢复建议
                if (errorDetails.recoverySuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.errorSuggestionsTitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )

                    errorDetails.recoverySuggestions.forEach { suggestion ->
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                }

                // 原始错误（可选，用于调试）
                if (errorDetails.type == ConnectionErrorType.UnknownError) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Technical details: ${errorDetails.originalMessage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (errorDetails.showHelpButton && errorDetails.helpUrl != null) {
                    TextButton(onClick = {
                        openUrl(errorDetails.helpUrl)
                        onDismiss()
                    }) {
                        Text(stringResource(Res.string.errorDialogHelp))
                    }
                }

                if (errorDetails.showRetryButton) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(Res.string.errorDialogRetry))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.errorDialogDismiss))
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
}

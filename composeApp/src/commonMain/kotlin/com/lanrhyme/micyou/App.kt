package com.lanrhyme.micyou

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun App(
    viewModel: MainViewModel? = null,
    onMinimize: () -> Unit = {},
    onClose: () -> Unit = {},
    onExitApp: () -> Unit = {},
    onHideApp: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    isBluetoothDisabled: Boolean = false
) {
    val platform = remember { getPlatform() }
    val isClient = platform.type == PlatformType.Android

    // Use passed viewModel or create one
    val finalViewModel = viewModel ?: if (isClient) viewModel { MainViewModel() } else remember { MainViewModel() }

    val uiState by finalViewModel.uiState.collectAsState()
    val seedColorObj = androidx.compose.ui.graphics.Color(uiState.seedColor.toInt())
    val strings = getStrings(uiState.language)

    val updateInfo = uiState.updateInfo
    val pocketMode = uiState.pocketMode
    val useSystemTitleBar = uiState.useSystemTitleBar
    val showFirstLaunchDialog = uiState.showFirstLaunchDialog
    val showVBCableDialog = uiState.showVBCableDialog
    val vbcableInstallProgress = uiState.vbcableInstallProgress

    CompositionLocalProvider(LocalAppStrings provides strings) {
        AppTheme(
            themeMode = uiState.themeMode,
            seedColor = seedColorObj,
            useDynamicColor = uiState.useDynamicColor,
            oledPureBlack = uiState.oledPureBlack
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
                var selectedMirror by remember { mutableStateOf(useMirrorDownload && updateInfo.mirrorUrl != null) }

                AlertDialog(
                    onDismissRequest = {
                        if (!isDownloading && !isInstalling) {
                            finalViewModel.dismissUpdateDialog()
                        }
                    },
                    title = { Text(strings.updateTitle) },
                    text = {
                        Column {
                            if (isFailed) {
                                Text(strings.updateDownloadFailed.replace("%s", updateError ?: ""))
                            } else if (isInstalling) {
                                Text(strings.updateInstalling)
                            } else if (isDownloading) {
                                Text(strings.updateDownloading)
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
                                Text(strings.updateMessage.replace("%s", updateInfo.versionName))

                                // Show download source selection if Mirror is available
                                if (updateInfo.mirrorUrl != null) {
                                    Spacer(Modifier.height(16.dp))
                                    Text(strings.mirrorDownloadLabel, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        RadioButton(
                                            selected = selectedMirror,
                                            onClick = { selectedMirror = true }
                                        )
                                        Text(strings.mirrorDownloadLabel, modifier = Modifier.padding(start = 4.dp))
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        RadioButton(
                                            selected = !selectedMirror,
                                            onClick = { selectedMirror = false }
                                        )
                                        Text(strings.githubDownloadLabel, modifier = Modifier.padding(start = 4.dp))
                                    }

                                    // Show CDK expiration warning if applicable
                                    updateInfo.cdkExpiredTime?.let { expiredTime ->
                                        val now = System.currentTimeMillis() / 1000
                                        val daysLeft = (expiredTime - now) / (24 * 60 * 60)
                                        if (daysLeft in 1..7) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                strings.mirrorCdkExpiredWarning,
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
                                Text(strings.updateGoToGitHub)
                            }
                        } else if (!isDownloading && !isInstalling) {
                            TextButton(onClick = {
                                finalViewModel.downloadAndInstallUpdate(selectedMirror)
                            }) {
                                Text(strings.updateNow)
                            }
                        }
                    },
                    dismissButton = {
                        if (!isDownloading && !isInstalling) {
                            TextButton(onClick = { finalViewModel.dismissUpdateDialog() }) {
                                Text(strings.updateLater)
                            }
                        }
                    }
                )
            }

            // First Launch Dialog
            if (showFirstLaunchDialog) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(strings.firstLaunchTitle) },
                    text = {
                        Column {
                            Text(strings.firstLaunchMessage)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            openUrl("https://www.bilibili.com/video/BV1MpNKz8ELw")
                            finalViewModel.dismissFirstLaunchDialog()
                        }) {
                            Text(strings.firstLaunchGuideButton)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            finalViewModel.dismissFirstLaunchDialog()
                        }) {
                            Text(strings.firstLaunchGotItButton)
                        }
                    }
                )
            }

            // VB-Cable Detection Dialog
            if (showVBCableDialog) {
                AlertDialog(
                    onDismissRequest = { finalViewModel.setShowVBCableDialog(false) },
                    title = { Text(strings.vbcableDetectTitle) },
                    text = {
                        Column {
                            Text(strings.vbcableDetectMessage)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            finalViewModel.setShowVBCableDialog(false)
                            finalViewModel.startVBCableInstallation()
                        }) {
                            Text(strings.vbcableAutoInstall)
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                openUrl("https://vb-audio.com/Cable/")
                                finalViewModel.setShowVBCableDialog(false)
                            }) {
                                Text(strings.vbcableManualDownload)
                            }
                            TextButton(onClick = {
                                finalViewModel.setShowVBCableDialog(false)
                            }) {
                                Text(strings.vbcableSkip)
                            }
                        }
                    }
                )
            }

            // VB-Cable Installation Progress Dialog
            if (vbcableInstallProgress != null) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(strings.installInstalling) },
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
    val strings = LocalAppStrings.current
    
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
                        text = strings.errors.errorSuggestionsTitle,
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
                        Text(strings.errors.errorDialogHelp)
                    }
                }
                
                if (errorDetails.showRetryButton) {
                    TextButton(onClick = onRetry) {
                        Text(strings.errors.errorDialogRetry)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.errors.errorDialogDismiss)
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

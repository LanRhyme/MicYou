package com.lanrhyme.micyou

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.lanrhyme.micyou.network.ConnectionErrorDetails
import com.lanrhyme.micyou.theme.AppTheme
import com.lanrhyme.micyou.theme.ThemeMode
import com.lanrhyme.micyou.ui.dialog.PermissionDialog
import com.lanrhyme.micyou.ui.MobileHome
import com.lanrhyme.micyou.update.UpdateInfo
import com.lanrhyme.micyou.util.currentTimeSeconds
import com.lanrhyme.micyou.util.formatBytes
import com.lanrhyme.micyou.util.setAppLocale
import com.lanrhyme.micyou.viewmodel.UpdateDownloadState
import com.lanrhyme.micyou.util.openUrl
import com.lanrhyme.micyou.util.PermissionState
import com.lanrhyme.micyou.viewmodel.MainViewModel
import androidx.activity.ComponentActivity

@Composable
fun App(
    viewModel: MainViewModel? = null,
    activity: ComponentActivity? = null,
    // Permission dialog parameters (Android)
    showPermissionDialog: Boolean = false,
    currentPermissions: List<PermissionState> = emptyList(),
    onRequestPermissions: (List<String>) -> Unit = {},
    onPermissionDialogDismiss: () -> Unit = {},
    // Flag to indicate permission dialog has been dismissed (to control first launch dialog timing)
    isPermissionDialogDismissed: Boolean = true
) {
    val finalViewModel = viewModel ?: viewModel { MainViewModel() }
    val uiState by finalViewModel.uiState.collectAsState()
    val languageCode = uiState.language.code

    // Track initial language to avoid unnecessary recreate on first composition
    val initialLanguageCode = remember { languageCode }
    LaunchedEffect(languageCode) {
        if (languageCode != initialLanguageCode) {
            setAppLocale(languageCode)
            activity?.recreate()
        }
    }

    key(languageCode) {
        val seedColorObj = androidx.compose.ui.graphics.Color(uiState.seedColor.toInt())
        val updateInfo = uiState.updateInfo
        val showFirstLaunchDialog = uiState.showFirstLaunchDialog && isPermissionDialogDismissed

        AppTheme(
            themeMode = uiState.themeMode,
            seedColor = seedColorObj,
            useDynamicColor = uiState.useDynamicColor,
            oledPureBlack = uiState.oledPureBlack,
            paletteStyle = uiState.paletteStyle,
            useExpressiveShapes = uiState.useExpressiveShapes
        ) {
            MobileHome(finalViewModel)

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
                    title = { Text(stringResource(R.string.updateTitle)) },
                    text = {
                        Column {
                            if (isFailed) {
                                Text(String.format(stringResource(R.string.updateDownloadFailed), updateError ?: ""))
                            } else if (isInstalling) {
                                Text(stringResource(R.string.updateInstalling))
                            } else if (isDownloading) {
                                Text(stringResource(R.string.updateDownloading))
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
                                Text(String.format(stringResource(R.string.updateMessage), updateInfo.versionName))

                                if (useMirrorDownload && updateInfo.mirrorUrl != null) {
                                    updateInfo.cdkExpiredTime?.let { expiredTime ->
                                        val now = currentTimeSeconds()
                                        val daysLeft = (expiredTime - now) / (24 * 60 * 60)
                                        if (daysLeft in 1..7) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                stringResource(R.string.mirrorCdkExpiredWarning),
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
                                Text(stringResource(R.string.updateGoToGitHub))
                            }
                        } else if (!isDownloading && !isInstalling) {
                            TextButton(onClick = {
                                finalViewModel.downloadAndInstallUpdate()
                            }) {
                                Text(stringResource(R.string.updateNow))
                            }
                        }
                    },
                    dismissButton = {
                        if (!isDownloading && !isInstalling) {
                            TextButton(onClick = { finalViewModel.dismissUpdateDialog() }) {
                                Text(stringResource(R.string.updateLater))
                            }
                        }
                    }
                )
            }

            // First Launch Dialog
            if (showFirstLaunchDialog) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(stringResource(R.string.firstLaunchTitle)) },
                    text = {
                        Column(
                            modifier = Modifier
                                .widthIn(min = 320.dp, max = 420.dp)
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.firstLaunchMessage),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = stringResource(R.string.firstLaunchQuickStartTitle),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // Step 1: Install PC Server
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(R.string.firstLaunchStep1Title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.firstLaunchStep1Desc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // Step 2: Connect
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(R.string.firstLaunchStep2Title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.firstLaunchStep2Desc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // Step 3: Start
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(R.string.firstLaunchStep3Title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.firstLaunchStep3Desc),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            openUrl("https://github.com/LanRhyme/MicYou")
                        }) {
                            Text(stringResource(R.string.firstLaunchDownloadPc))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            finalViewModel.dismissFirstLaunchDialog()
                        }) {
                            Text(stringResource(R.string.firstLaunchGotItButton))
                        }
                    }
                )
            }

            // Permission Dialog (Android)
            if (showPermissionDialog && currentPermissions.isNotEmpty()) {
                PermissionDialog(
                    activity = activity,
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

/**
 * 连接错误对话框组件
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
                Text(
                    text = errorDetails.localizedMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (errorDetails.recoverySuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.errorSuggestionsTitle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    errorDetails.recoverySuggestions.forEach { suggestion ->
                        Text(
                            text = "• $suggestion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.errorDialogRetry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.errorDialogDismiss))
            }
        }
    )
}
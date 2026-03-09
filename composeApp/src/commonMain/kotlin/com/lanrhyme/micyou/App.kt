package com.lanrhyme.micyou

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    val themeMode by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.themeMode }
    }
    val seedColor by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.seedColor }
    }
    val useDynamicColor by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.useDynamicColor }
    }
    val oledPureBlack by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.oledPureBlack }
    }
    
    // Convert Long color to Color object
    val seedColorObj = androidx.compose.ui.graphics.Color(seedColor.toInt())
    
    val language by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.language }
    }
    
    val strings = getStrings(language)

    val uiState by finalViewModel.uiState.collectAsState()
    val newVersionAvailable = uiState.newVersionAvailable
    val pocketMode = uiState.pocketMode
    val useSystemTitleBar = uiState.useSystemTitleBar
    val showFirstLaunchDialog = uiState.showFirstLaunchDialog

    CompositionLocalProvider(LocalAppStrings provides strings) {
        AppTheme(
            themeMode = themeMode,
            seedColor = seedColorObj,
            useDynamicColor = useDynamicColor,
            oledPureBlack = oledPureBlack
        ) {
            if (platform.type == PlatformType.Android) {
                MobileHome(finalViewModel)
            } else {
                var showSettings by remember { mutableStateOf(false) }
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = if (useSystemTitleBar) RoundedCornerShape(0.dp) else RoundedCornerShape(22.dp)
                ) {
                    AnimatedContent(
                        targetState = showSettings,
                        transitionSpec = {
                        // smoother horizontal slide + fade with consistent easing
                        val enter = slideInHorizontally(
                            initialOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(360, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing))

                        val exit = slideOutHorizontally(
                            targetOffsetX = { fullWidth -> -fullWidth / 3 },
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))

                        if (targetState) enter togetherWith exit
                        else {
                            val enterBack = slideInHorizontally(
                                initialOffsetX = { fullWidth -> -fullWidth / 3 },
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing))

                            val exitBack = slideOutHorizontally(
                                targetOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(360, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))

                            enterBack togetherWith exitBack
                        }
                    }
                ) { isSettings ->
                    if (isSettings) {
                        // On desktop, open settings in a separate window via callback
                        LaunchedEffect(Unit) {
                            onOpenSettings()
                            showSettings = false
                        }
                        // render an empty container while the external window opens
                        Box(modifier = Modifier.fillMaxSize()) {}
                    } else {
                        if (pocketMode) {
                            DesktopHome(
                                viewModel = finalViewModel,
                                onMinimize = onMinimize,
                                onClose = onClose,
                                onExitApp = onExitApp,
                                onHideApp = onHideApp,
                                onOpenSettings = { showSettings = true },
                                isBluetoothDisabled = isBluetoothDisabled
                            )
                        } else {
                            DesktopHomeEnhanced(
                                viewModel = finalViewModel,
                                onMinimize = onMinimize,
                                onClose = onClose,
                                onExitApp = onExitApp,
                                onHideApp = onHideApp,
                                onOpenSettings = { showSettings = true },
                                isBluetoothDisabled = isBluetoothDisabled
                            )
                        }
                    }
                }
                }
            }

            // Update Dialog
            if (newVersionAvailable != null) {
                val downloadState = uiState.updateDownloadState
                val downloadProgress = uiState.updateDownloadProgress
                val downloadedBytes = uiState.updateDownloadedBytes
                val totalBytes = uiState.updateTotalBytes
                val updateError = uiState.updateErrorMessage
                val isDownloading = downloadState == UpdateDownloadState.Downloading
                val isInstalling = downloadState == UpdateDownloadState.Installing
                val isFailed = downloadState == UpdateDownloadState.Failed

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
                                Text(strings.updateMessage.replace("%s", newVersionAvailable.tagName))
                            }
                        }
                    },
                    confirmButton = {
                        if (isFailed) {
                            TextButton(onClick = {
                                openUrl(newVersionAvailable.htmlUrl)
                                finalViewModel.dismissUpdateDialog()
                            }) {
                                Text(strings.updateGoToGitHub)
                            }
                        } else if (!isDownloading && !isInstalling) {
                            TextButton(onClick = {
                                finalViewModel.downloadAndInstallUpdate()
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

            // First Launch Dialog (Desktop only)
            if (showFirstLaunchDialog && platform.type != PlatformType.Android) {
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
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceAtMost(units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
}

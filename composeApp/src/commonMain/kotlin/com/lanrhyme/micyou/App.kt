package com.lanrhyme.micyou

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

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
    
    // Convert Long color to Color object
    val seedColorObj = androidx.compose.ui.graphics.Color(seedColor.toInt())
    
    val language by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.language }
    }
    
    val strings = getStrings(language)

    val uiState by finalViewModel.uiState.collectAsState()
    val newVersionAvailable = uiState.newVersionAvailable

    CompositionLocalProvider(LocalAppStrings provides strings) {
        AppTheme(themeMode = themeMode, seedColor = seedColorObj, useDynamicColor = useDynamicColor) {
            if (platform.type == PlatformType.Android) {
                MobileHome(finalViewModel)
            } else {
                DesktopHome(
                    viewModel = finalViewModel,
                    onMinimize = onMinimize,
                    onClose = onClose,
                    onExitApp = onExitApp,
                    onHideApp = onHideApp,
                    onOpenSettings = onOpenSettings,
                    isBluetoothDisabled = isBluetoothDisabled
                )
            }

            // Update Dialog
            if (newVersionAvailable != null) {
                AlertDialog(
                    onDismissRequest = { finalViewModel.dismissUpdateDialog() },
                    title = { Text(strings.updateTitle) },
                    text = { Text(strings.updateMessage.replace("%s", newVersionAvailable.tagName)) },
                    confirmButton = {
                        TextButton(onClick = {
                            openUrl(newVersionAvailable.htmlUrl)
                            finalViewModel.dismissUpdateDialog()
                        }) {
                            Text(strings.updateNow)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { finalViewModel.dismissUpdateDialog() }) {
                            Text(strings.updateLater)
                        }
                    }
                )
            }
        }
    }
}


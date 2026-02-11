package com.lanrhyme.micyou

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

fun main() {
    application {
        var isSettingsOpen by remember { mutableStateOf(false) }
    
    // Create shared ViewModel instance
    val viewModel = remember { MainViewModel() }

    // Main Window State
    val windowState = rememberWindowState(
        width = 600.dp, 
        height = 240.dp,
        position = WindowPosition(Alignment.Center)
    )

    // Main Window (Undecorated)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "MicYou",
        icon = painterResource(Res.drawable.app_icon),
        undecorated = true,
        transparent = true, // Allows rounded corners via Surface in DesktopHome
        resizable = false
    ) {
        WindowDraggableArea {
            App(
                viewModel = viewModel,
                onMinimize = { windowState.isMinimized = true },
                onClose = ::exitApplication,
                onOpenSettings = { isSettingsOpen = true }
            )
        }
    }

    // Settings Window
    if (isSettingsOpen) {
        val settingsState = rememberWindowState(
            width = 530.dp,
            height = 500.dp,
            position = WindowPosition(Alignment.Center)
        )
        
        Window(
            onCloseRequest = { isSettingsOpen = false },
            state = settingsState,
            title = "Settings",
            icon = painterResource(Res.drawable.app_icon),
            resizable = false
        ) {
            // Re-use theme logic from AppTheme but apply to settings window content
            val themeMode by viewModel.uiState.collectAsState().let { state ->
                derivedStateOf { state.value.themeMode }
            }
            val seedColor by viewModel.uiState.collectAsState().let { state ->
                derivedStateOf { state.value.seedColor }
            }
            val language by viewModel.uiState.collectAsState().let { state ->
                derivedStateOf { state.value.language }
            }
            val seedColorObj = androidx.compose.ui.graphics.Color(seedColor.toInt())
            val strings = getStrings(language)

            CompositionLocalProvider(LocalAppStrings provides strings) {
                AppTheme(themeMode = themeMode, seedColor = seedColorObj) {
                    DesktopSettings(
                        viewModel = viewModel,
                        onClose = { isSettingsOpen = false }
                    )
                }
            }
        }
    }
}
}


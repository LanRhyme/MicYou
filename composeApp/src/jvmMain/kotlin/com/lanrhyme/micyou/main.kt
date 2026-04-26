package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kdroid.composetray.tray.api.Tray
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.util.JvmLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import java.awt.Font
import java.awt.Toolkit
import javax.swing.UIManager
import kotlin.system.exitProcess
import micyou.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalResourceApi::class)
fun main() {
    JvmLogger.init()
    Logger.init(JvmLogger)
    System.setProperty("file.encoding", "UTF-8")
    System.setProperty("sun.jnu.encoding", "UTF-8")
    
    System.setProperty("sun.java2d.noddraw", "true")
    System.setProperty("sun.java2d.d3d", "false")

    System.setProperty( "apple.awt.application.name", "MicYou" )
    System.setProperty( "apple.awt.application.appearance", "system" )

    if (PlatformInfo.isMacOS) {
        System.setProperty("skiko.renderApi", "METAL")
    } else {
        System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    }

    System.setProperty("skiko.vsync", "false")
    System.setProperty("skiko.fps.enabled", "false")

    try {
        var fontName = "Microsoft YaHei"
        
        if (PlatformInfo.isLinux) {
            fontName = "WenQuanYi Micro Hei"
        }

        if (PlatformInfo.isMacOS) {
            fontName = "SF Pro Display"
        }
    val font = Font(fontName, Font.PLAIN, 12)
    val keys = arrayOf(
            "MenuItem.font", "Menu.font", "PopupMenu.font", 
            "CheckBoxMenuItem.font", "RadioButtonMenuItem.font",
            "Label.font", "Button.font", "ToolTip.font"
        )
        
        for (key in keys) {
            UIManager.put(key, font)
        }
        
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (e: Exception) {
        Logger.w("Main", "Failed to set system look and feel: ${e.message}")
    }

    Logger.i("Main", "App started")
    application {
        val viewModel = remember { MainViewModel() }
    var isVisible by remember { mutableStateOf(true) }
    var showSettingsWindow by remember { mutableStateOf(false) }

        // Helper function for app exit with timeout protection
        val exitApp: () -> Unit = {
            runBlocking {
                withTimeoutOrNull(Constants.EXIT_CLEANUP_TIMEOUT_MS) {
                    VirtualAudioDeviceManager.setSystemDefaultMicrophone(toCable = false)
                } ?: Logger.w("Main", "Timeout while restoring system default microphone")
            }
            exitProcess(0)
        }
    val uiState by viewModel.uiState.collectAsState()
    val isStreaming = uiState.streamState == StreamState.Streaming || uiState.streamState == StreamState.Connecting

        val icon = painterResource(Res.drawable.app_icon)
        
        Tray(
            icon = Res.drawable.app_icon,
            tooltip = stringResource(Res.string.appName)
        ) {
            Item(
                label = if (isVisible) stringResource(Res.string.trayHide) else stringResource(Res.string.trayShow)
            ) {
                isVisible = !isVisible
            }

            Item(
                label = if (isStreaming) stringResource(Res.string.stop) else stringResource(Res.string.start)
            ) {
                viewModel.toggleStream()
            }

            Item(
                label = stringResource(Res.string.trayExit)
            ) {
                exitApp()
            }
        }
    val windowWidth = if (uiState.pocketMode) 650.dp else 850.dp
        val windowHeight = if (uiState.pocketMode) 300.dp else 650.dp
        val windowState = rememberWindowState(
            width = windowWidth,
            height = windowHeight,
            position = WindowPosition(Alignment.Center)
        )

        LaunchedEffect(uiState.pocketMode) {
            windowState.size = androidx.compose.ui.unit.DpSize(windowWidth, windowHeight)
        }

        if (isVisible) {
            key(uiState.useSystemTitleBar) {
            Window(
                onCloseRequest = { viewModel.handleCloseRequest(onExit = exitApp, onHide = { isVisible = false }) },
                state = windowState,
                title = stringResource(Res.string.appName),
                icon = icon,
                undecorated = !uiState.useSystemTitleBar,
                transparent = !uiState.useSystemTitleBar,
                resizable = false
            ) {
                val windowContent: @Composable () -> Unit = {
                    val isBluetoothDisabled = PlatformInfo.isMacOS && PlatformInfo.isArm64
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (!uiState.useSystemTitleBar) Modifier.padding(8.dp) else Modifier)
                    ) {
                        App(
                            viewModel = viewModel,
                            onMinimize = { windowState.isMinimized = true },
                            onClose = { viewModel.handleCloseRequest(onExit = exitApp, onHide = { isVisible = false }) },
                            onExitApp = exitApp,
                            onHideApp = { isVisible = false },
                            onOpenSettings = { if (uiState.pocketMode) showSettingsWindow = true },
                            isBluetoothDisabled = isBluetoothDisabled
                        )
                    }
                }
                if (!uiState.useSystemTitleBar) WindowDraggableArea { windowContent() } else windowContent()
            }
            }
        }

        if (uiState.showCloseConfirmDialog) {
            val closeConfirmState = rememberWindowState(
                width = 500.dp,
                height = 250.dp,
                position = WindowPosition(Alignment.Center)
            )

            Window(
                onCloseRequest = { viewModel.setShowCloseConfirmDialog(false) },
                state = closeConfirmState,
                title = stringResource(Res.string.closeConfirmTitle),
                icon = icon,
                undecorated = true,
                transparent = true,
                resizable = false
            ) {
                val seedColorObj = androidx.compose.ui.graphics.Color(uiState.seedColor.toInt())
                AppTheme(
                    themeMode = uiState.themeMode,
                    seedColor = seedColorObj,
                    useDynamicColor = uiState.useDynamicColor,
                    oledPureBlack = uiState.oledPureBlack,
                    paletteStyle = uiState.paletteStyle,
                    useExpressiveShapes = uiState.useExpressiveShapes
                ) {
                    CloseConfirmDialog(
                        onDismiss = { viewModel.setShowCloseConfirmDialog(false) },
                        onMinimize = { viewModel.confirmCloseAction(CloseAction.Minimize, uiState.rememberCloseAction, onExit = exitApp, onHide = { isVisible = false }) },
                        onExit = { viewModel.confirmCloseAction(CloseAction.Exit, uiState.rememberCloseAction, onExit = exitApp, onHide = { isVisible = false }) },
                        rememberCloseAction = uiState.rememberCloseAction,
                        onRememberChange = { viewModel.setRememberCloseAction(it) }
                    )
                }
            }
        }

        if (showSettingsWindow && uiState.pocketMode) {
            val settingsWindowState = rememberWindowState(width = 800.dp, height = 600.dp, position = WindowPosition(Alignment.Center))
            Window(
                onCloseRequest = { showSettingsWindow = false },
                state = settingsWindowState,
                title = stringResource(Res.string.settingsTitle),
                icon = icon,
                undecorated = false,
                resizable = true
            ) {
                val seedColorObj = androidx.compose.ui.graphics.Color(uiState.seedColor.toInt())
                AppTheme(
                    themeMode = uiState.themeMode,
                    seedColor = seedColorObj,
                    useDynamicColor = uiState.useDynamicColor,
                    oledPureBlack = uiState.oledPureBlack,
                    paletteStyle = uiState.paletteStyle,
                    useExpressiveShapes = uiState.useExpressiveShapes
                ) {
                    DesktopSettings(viewModel = viewModel, onClose = { showSettingsWindow = false })
                }
            }
        }

        if (uiState.floatingWindowEnabled) {
            FloatingMicWindowContainer(viewModel = viewModel)
        }
    }
}

@Composable
private fun FloatingMicWindowContainer(
    viewModel: MainViewModel
) {
    val screenSize = Toolkit.getDefaultToolkit().screenSize

    val uiState by viewModel.uiState.collectAsState()
    val themeMode = uiState.themeMode
    val seedColor = uiState.seedColor
    val useDynamicColor = uiState.useDynamicColor
    val oledPureBlack = uiState.oledPureBlack
    val paletteStyle = uiState.paletteStyle
    val useExpressiveShapes = uiState.useExpressiveShapes
    val seedColorObj = androidx.compose.ui.graphics.Color(seedColor.toInt())

    Window(
        onCloseRequest = { viewModel.setFloatingWindowEnabled(false) },
        title = "",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true
    ) {
        val window = this.window

        LaunchedEffect(Unit) {
            window.setSize(36, 36)
            window.setLocation(screenSize.width - 60, 60)
        }

        AppTheme(
            themeMode = themeMode,
            seedColor = seedColorObj,
            useDynamicColor = useDynamicColor,
            oledPureBlack = oledPureBlack,
            paletteStyle = paletteStyle,
            useExpressiveShapes = useExpressiveShapes
        ) {
            FloatingMicWindow(
                viewModel = viewModel,
                window = window,
                onClose = { viewModel.setFloatingWindowEnabled(false) }
            )
        }
    }
}

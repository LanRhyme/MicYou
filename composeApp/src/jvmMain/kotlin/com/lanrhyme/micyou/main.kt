package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import java.awt.Font
import java.awt.Toolkit
import javax.swing.UIManager
import kotlin.system.exitProcess

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
        e.printStackTrace()
    }

    Logger.i("Main", "App started")
    application {
        val viewModel = remember { MainViewModel() }
        var isVisible by remember { mutableStateOf(true) }

        val language by viewModel.uiState.collectAsState().let { state ->
            derivedStateOf { state.value.language }
        }
        val strings = getStrings(language)

        val streamState by viewModel.uiState.collectAsState().let { state ->
            derivedStateOf { state.value.streamState }
        }
        val isStreaming = streamState == StreamState.Streaming || streamState == StreamState.Connecting

        val icon = painterResource(Res.drawable.app_icon)
        
        Tray(
            icon = Res.drawable.app_icon,
            tooltip = strings.appName
        ) {
            Item(
                label = if (isVisible) strings.trayHide else strings.trayShow
            ) {
                isVisible = !isVisible
            }

            Item(
                label = if (isStreaming) strings.stop else strings.start
            ) {
                viewModel.toggleStream()
            }

            Item(
                label = strings.trayExit
            ) {
                runBlocking {
                    VBCableManager.setSystemDefaultMicrophone(toCable = false)
                }
                exitProcess(0)
            }
        }

        val pocketMode by viewModel.uiState.collectAsState().let { state ->
            derivedStateOf { state.value.pocketMode }
        }

        val useSystemTitleBar by viewModel.uiState.collectAsState().let { state ->
            derivedStateOf { state.value.useSystemTitleBar }
        }

        val windowState = rememberWindowState(
            width = if (pocketMode) 600.dp else 850.dp,
            height = if (pocketMode) 250.dp else 650.dp,
            position = WindowPosition(Alignment.Center)
        )

        LaunchedEffect(pocketMode) {
            windowState.size = androidx.compose.ui.unit.DpSize(
                if (pocketMode) 600.dp else 850.dp,
                if (pocketMode) 250.dp else 650.dp
            )
        }

        if (isVisible) {
            key(useSystemTitleBar) {
            Window(
                onCloseRequest = { 
                    viewModel.handleCloseRequest(
                        onExit = { 
                            runBlocking {
                                VBCableManager.setSystemDefaultMicrophone(toCable = false)
                            }
                            exitProcess(0)
                        },
                        onHide = { isVisible = false }
                    )
                },
                state = windowState,
                title = strings.appName,
                icon = icon,
                undecorated = !useSystemTitleBar,
                transparent = !useSystemTitleBar,
                resizable = false
            ) {
                val windowContent: @Composable () -> Unit = {
                    // Apple Silicon Mac cannot use BlueCove without Rosetta 2
                    val isBluetoothDisabled = PlatformInfo.isMacOS && PlatformInfo.isArm64

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (!useSystemTitleBar) Modifier.padding(8.dp) else Modifier)
                    ) {
                        App(
                            viewModel = viewModel,
                            onMinimize = { windowState.isMinimized = true },
                        onClose = { 
                            viewModel.handleCloseRequest(
                                onExit = { 
                                    runBlocking {
                                        VBCableManager.setSystemDefaultMicrophone(toCable = false)
                                    }
                                    exitProcess(0)
                                },
                                onHide = { isVisible = false }
                            )
                        },
                        onExitApp = { 
                            runBlocking {
                                VBCableManager.setSystemDefaultMicrophone(toCable = false)
                            }
                            exitProcess(0)
                        },
                        onHideApp = { isVisible = false },
                        isBluetoothDisabled = isBluetoothDisabled
                    )
                    }
                }

                if (!useSystemTitleBar) {
                    WindowDraggableArea {
                        windowContent()
                    }
                } else {
                    windowContent()
                }
            }
            }
        }

        val showCloseConfirmDialog by viewModel.uiState.collectAsState().let { state ->
            derivedStateOf { state.value.showCloseConfirmDialog }
        }

        if (showCloseConfirmDialog) {
            val closeConfirmState = rememberWindowState(
                width = 500.dp,
                height = 250.dp,
                position = WindowPosition(Alignment.Center)
            )

            Window(
                onCloseRequest = { viewModel.setShowCloseConfirmDialog(false) },
                state = closeConfirmState,
                title = strings.closeConfirmTitle,
                icon = icon,
                undecorated = true,
                transparent = true,
                resizable = false
            ) {
                val themeMode by viewModel.uiState.collectAsState().let { state ->
                    derivedStateOf { state.value.themeMode }
                }
                val seedColor by viewModel.uiState.collectAsState().let { state ->
                    derivedStateOf { state.value.seedColor }
                }
                val oledPureBlack by viewModel.uiState.collectAsState().let { state ->
                    derivedStateOf { state.value.oledPureBlack }
                }
                val rememberCloseAction by viewModel.uiState.collectAsState().let { state ->
                    derivedStateOf { state.value.rememberCloseAction }
                }
                val seedColorObj = androidx.compose.ui.graphics.Color(seedColor.toInt())

                CompositionLocalProvider(LocalAppStrings provides strings) {
                    AppTheme(themeMode = themeMode, seedColor = seedColorObj, oledPureBlack = oledPureBlack) {
                        CloseConfirmDialog(
                            onDismiss = { viewModel.setShowCloseConfirmDialog(false) },
                            onMinimize = {
                                viewModel.confirmCloseAction(
                                    CloseAction.Minimize,
                                    rememberCloseAction,
                                    onExit = {
                                        runBlocking {
                                            VBCableManager.setSystemDefaultMicrophone(toCable = false)
                                        }
                                        exitProcess(0)
                                    },
                                    onHide = { isVisible = false }
                                )
                            },
                            onExit = {
                                viewModel.confirmCloseAction(
                                    CloseAction.Exit,
                                    rememberCloseAction,
                                    onExit = {
                                        runBlocking {
                                            VBCableManager.setSystemDefaultMicrophone(toCable = false)
                                        }
                                        exitProcess(0)
                                    },
                                    onHide = { isVisible = false }
                                )
                            },
                            rememberCloseAction = rememberCloseAction,
                            onRememberChange = { viewModel.setRememberCloseAction(it) }
                        )
                    }
                }
            }
        }

        val floatingWindowEnabled by viewModel.uiState.collectAsState().let { state ->
            derivedStateOf { state.value.floatingWindowEnabled }
        }

        if (floatingWindowEnabled) {
            FloatingMicWindowContainer(
                viewModel = viewModel,
                strings = strings
            )
        }
    }
}

@Composable
private fun FloatingMicWindowContainer(
    viewModel: MainViewModel,
    strings: AppStrings
) {
    val screenSize = Toolkit.getDefaultToolkit().screenSize
    
    val uiState by viewModel.uiState.collectAsState()
    val themeMode = uiState.themeMode
    val seedColor = uiState.seedColor
    val oledPureBlack = uiState.oledPureBlack
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
        
        CompositionLocalProvider(LocalAppStrings provides strings) {
            AppTheme(themeMode = themeMode, seedColor = seedColorObj, oledPureBlack = oledPureBlack) {
                FloatingMicWindow(
                    viewModel = viewModel, 
                    window = window,
                    onClose = { viewModel.setFloatingWindowEnabled(false) }
                )
            }
        }
    }
}

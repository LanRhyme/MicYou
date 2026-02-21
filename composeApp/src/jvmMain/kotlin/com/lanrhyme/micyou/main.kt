package com.lanrhyme.micyou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import com.lanrhyme.micyou.platform.PlatformInfo
import com.lanrhyme.micyou.util.JvmLogger
import dorkbox.systemTray.MenuItem
import kotlinx.coroutines.runBlocking
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource
import java.awt.Font
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO
import javax.swing.UIManager
import kotlin.system.exitProcess

fun main() {
    JvmLogger.init()
    Logger.init(JvmLogger)
    System.setProperty("file.encoding", "UTF-8")
    System.setProperty("sun.jnu.encoding", "UTF-8")
    
    System.setProperty("sun.java2d.noddraw", "true")
    System.setProperty("sun.java2d.d3d", "false")
    
    System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    System.setProperty("skiko.vsync", "false")
    System.setProperty("skiko.fps.enabled", "false")

    try {
        var fontName = "Microsoft YaHei"
        
        if (PlatformInfo.isLinux) {
            fontName = "WenQuanYi Micro Hei"
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
        var isSettingsOpen by remember { mutableStateOf(false) }
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
        
        val systemTrayState = remember { mutableStateOf<dorkbox.systemTray.SystemTray?>(null) }
        
        if (PlatformInfo.isLinux) {
            DisposableEffect(Unit) {
                var tray: dorkbox.systemTray.SystemTray? = null
                try {
                    tray = dorkbox.systemTray.SystemTray.get()
                } catch (e: Exception) {
                    Logger.e("Tray", "Failed to initialize SystemTray: ${e.message}")
                }

                if (tray == null) {
                    Logger.w("Tray", "System tray is not supported on this platform.")
                } else {
                    systemTrayState.value = tray
                    
                    val resourcePath = "composeResources/micyou.composeapp.generated.resources/drawable/app_icon.png"
                    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
                    if (stream != null) {
                        try {
                            tray.setImage(stream)
                        } catch (e: Exception) {
                            Logger.e("Tray", "Failed to set tray image: ${e.message}")
                        }
                    } else {
                        Logger.e("Tray", "Icon resource not found at $resourcePath")
                    }
                    
                    tray.status = "MicYou"
                }
                
                onDispose {
                    systemTrayState.value?.shutdown()
                }
            }
            
            val tray = systemTrayState.value
            if (tray != null) {
                val showHideItem = remember(tray) { 
                    MenuItem(strings.trayShow) { } 
                }
                val streamItem = remember(tray) { 
                    MenuItem(strings.start) { } 
                }
                val settingsItem = remember(tray) { 
                    MenuItem(strings.settingsTitle) { } 
                }
                val exitItem = remember(tray) { 
                    MenuItem(strings.trayExit) { } 
                }
                
                DisposableEffect(tray) {
                    tray.menu.add(showHideItem)
                    tray.menu.add(streamItem)
                    tray.menu.add(settingsItem)
                    tray.menu.add(exitItem)
                    onDispose { }
                }
                
                androidx.compose.runtime.SideEffect {
                    showHideItem.text = if (isVisible) strings.trayHide else strings.trayShow
                    showHideItem.setCallback { 
                        isVisible = !isVisible 
                    }
                    
                    streamItem.text = if (isStreaming) strings.stop else strings.start
                    streamItem.setCallback { 
                        viewModel.toggleStream() 
                    }
                    
                    settingsItem.text = strings.settingsTitle
                    settingsItem.setCallback {
                        isSettingsOpen = true
                        isVisible = true
                    }
                    
                    exitItem.text = strings.trayExit
                    exitItem.setCallback {
                        runBlocking {
                            VBCableManager.setSystemDefaultMicrophone(toCable = false)
                        }
                        exitProcess(0)
                    }
                }
            }
        } else {
            var isTrayMenuOpen by remember { mutableStateOf(false) }
            var trayMenuPosition by remember { mutableStateOf<WindowPosition>(WindowPosition(Alignment.Center)) }

            DisposableEffect(Unit) {
                if (!java.awt.SystemTray.isSupported()) {
                    Logger.w("Tray", "System tray is not supported on this platform.")
                    return@DisposableEffect onDispose {}
                }

                val tray = java.awt.SystemTray.getSystemTray()
                val image = try {
                    val resourcePath = "composeResources/micyou.composeapp.generated.resources/drawable/app_icon.png"
                    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
                    if (stream != null) {
                        ImageIO.read(stream)
                    } else {
                         Logger.e("Tray", "Icon resource not found at $resourcePath")
                         null
                    }
                } catch (e: Exception) {
                    Logger.e("Tray", "Failed to load icon: ${e.message}")
                    null
                }

                if (image != null) {
                    val trayIcon = TrayIcon(image, "MicYou")
                    trayIcon.isImageAutoSize = true
                    
                    trayIcon.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (e.button == MouseEvent.BUTTON1) {
                                isVisible = !isVisible
                            } else if (e.button == MouseEvent.BUTTON3) {
                                val screenX = e.xOnScreen
                                val screenY = e.yOnScreen
                                Logger.d("Tray", "Right click at screen position: ($screenX, $screenY)")
                                trayMenuPosition = WindowPosition(screenX.dp, screenY.dp)
                                isTrayMenuOpen = true
                            }
                        }
                    })
                    
                    try {
                        tray.add(trayIcon)
                    } catch (e: Exception) {
                        Logger.e("Tray", "Failed to add tray icon: ${e.message}")
                    }
                    
                    onDispose {
                        tray.remove(trayIcon)
                    }
                } else {
                    onDispose {}
                }
            }

            if (isTrayMenuOpen) {
                Logger.d("Tray", "Opening tray menu window at: $trayMenuPosition")
                Window(
                    onCloseRequest = { 
                        Logger.d("Tray", "Tray menu close requested")
                        isTrayMenuOpen = false 
                    },
                    visible = true,
                    title = "Tray Menu",
                    state = rememberWindowState(
                        position = trayMenuPosition,
                        width = 160.dp,
                        height = 180.dp
                    ),
                    undecorated = true,
                    transparent = false,
                    alwaysOnTop = true,
                    resizable = false,
                    focusable = true
                ) {
                    DisposableEffect(Unit) {
                        Logger.d("Tray", "Tray menu window DisposableEffect initialized")
                        val window = this@Window.window
                        var hasGainedFocus = false
                        val focusListener = object : WindowAdapter() {
                            override fun windowGainedFocus(e: WindowEvent?) {
                                hasGainedFocus = true
                                Logger.d("Tray", "Tray menu window gained focus")
                            }
                            override fun windowLostFocus(e: WindowEvent?) {
                                Logger.d("Tray", "Tray menu window lost focus, hasGainedFocus=$hasGainedFocus")
                                if (hasGainedFocus) {
                                    isTrayMenuOpen = false
                                }
                            }
                        }
                        window.addWindowFocusListener(focusListener)
                        onDispose {
                            window.removeWindowFocusListener(focusListener)
                        }
                    }
                    
                    val themeMode by viewModel.uiState.collectAsState().let { state ->
                        derivedStateOf { state.value.themeMode }
                    }
                    val seedColor by viewModel.uiState.collectAsState().let { state ->
                        derivedStateOf { state.value.seedColor }
                    }
                    val seedColorObj = androidx.compose.ui.graphics.Color(seedColor.toInt())
                    
                    AppTheme(themeMode = themeMode, seedColor = seedColorObj) {
                        Card(
                            elevation = 4.dp,
                            shape = RoundedCornerShape(8.dp),
                            backgroundColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 8.dp)
                            ) {
                                @Composable
                                fun TrayMenuItem(text: String, onClick: () -> Unit) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { 
                                                onClick()
                                                isTrayMenuOpen = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = text,
                                            fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                TrayMenuItem(
                                    text = if (isVisible) strings.trayHide else strings.trayShow,
                                    onClick = { isVisible = !isVisible }
                                )
                                TrayMenuItem(
                                    text = if (isStreaming) strings.stop else strings.start,
                                    onClick = { viewModel.toggleStream() }
                                )
                                TrayMenuItem(
                                    text = strings.settingsTitle,
                                    onClick = {
                                        isSettingsOpen = true
                                        isVisible = true
                                    }
                                )
                                TrayMenuItem(
                                    text = strings.trayExit,
                                    onClick = {
                                        runBlocking {
                                            VBCableManager.setSystemDefaultMicrophone(toCable = false)
                                        }
                                        exitProcess(0)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        val windowState = rememberWindowState(
            width = 600.dp, 
            height = 240.dp,
            position = WindowPosition(Alignment.Center)
        )

        if (isVisible) {
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
                undecorated = true,
                transparent = true,
                resizable = false
            ) {
                WindowDraggableArea {
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
                        onOpenSettings = { isSettingsOpen = true }
                    )
                }
            }
        }

        if (isSettingsOpen) {
            val settingsState = rememberWindowState(
                width = 530.dp,
                height = 500.dp,
                position = WindowPosition(Alignment.Center)
            )
            
            Window(
                onCloseRequest = { isSettingsOpen = false },
                state = settingsState,
                title = strings.settingsTitle,
                icon = icon,
                resizable = false
            ) {
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

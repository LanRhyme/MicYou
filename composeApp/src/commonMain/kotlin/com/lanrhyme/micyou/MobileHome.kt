package com.lanrhyme.micyou

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.animation.EasingFunctions
import com.lanrhyme.micyou.animation.rememberBreathAnimation
import com.lanrhyme.micyou.animation.rememberGlowAnimation
import com.lanrhyme.micyou.animation.rememberPulseAnimation
import com.lanrhyme.micyou.animation.rememberRotationAnimation
import com.lanrhyme.micyou.animation.rememberWaveAnimation
import com.lanrhyme.micyou.plugin.PluginInfo
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.icon_pip
import androidx.compose.ui.platform.LocalFocusManager
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import micyou.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHome(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val platform = remember { getPlatform() }
    val isClient = platform.type == PlatformType.Android
    val snackbarHostState = remember { SnackbarHostState() }
    val isDarkTheme = isDarkThemeActive(state.themeMode)
    val forcePureBlackBackground = state.oledPureBlack && isDarkTheme
    
    var showSettings by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }

    // Handle Android system back gesture to close settings page (no-op on desktop)
    BackHandlerCompat(enabled = showSettings) {
        showSettings = false
    }
    val hazeState = if (state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground) {
        rememberHazeState()
    } else null
    
    LaunchedEffect(Unit) {
        delay(100)
        contentVisible = true
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    val focusManager = LocalFocusManager.current
    LaunchedEffect(showSettings) {
        if (showSettings) {
            focusManager.clearFocus()
        }
    }

    // settings overlay handled below via AnimatedVisibility so exit animation can run

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            CustomBackground(
                settings = state.backgroundSettings,
                modifier = Modifier.fillMaxSize(),
                hazeState = hazeState,
                forcePureBlackBackground = forcePureBlackBackground
            )
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                AnimatedCardVisibility(
                    visible = contentVisible,
                    delayMillis = 50
                ) {
                    MobileHeaderSection(
                        platform = platform,
                        state = state,
                        onOpenSettings = { showSettings = true },
                                                cardOpacity = state.backgroundSettings.cardOpacity,
                        hazeState = hazeState
                    )
                }

                // Connection config
                AnimatedCardVisibility(
                    visible = contentVisible,
                    delayMillis = 150
                ) {
                    ConnectionConfigCard(
                        state = state,
                        viewModel = viewModel,
                        isClient = isClient,
                                                cardOpacity = state.backgroundSettings.cardOpacity,
                        hazeState = hazeState
                    )
                }

                // Main control
                AnimatedCardVisibility(
                    visible = contentVisible,
                    delayMillis = 250,
                    modifier = Modifier.weight(1f)
                ) {
                    MainControlCard(
                        state = state,
                        viewModel = viewModel,
                        audioLevel = audioLevel,
                                                cardOpacity = state.backgroundSettings.cardOpacity,
                        hazeState = hazeState
                    )
                }

                // Bottom bar (mute + settings)
                AnimatedCardVisibility(
                    visible = contentVisible,
                    delayMillis = 350
                ) {
                    MobileBottomBar(
                        state = state,
                        viewModel = viewModel,
                                                cardOpacity = state.backgroundSettings.cardOpacity,
                        hazeState = hazeState
                    )
                }
            }
            // Settings page overlay
            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(360, easing = EasingFunctions.EaseOutExpo)
                ) + fadeIn(animationSpec = tween(240)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300, easing = EasingFunctions.EaseInOutExpo)
                ) + fadeOut(animationSpec = tween(200))
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp
                ) {
                    DesktopSettings(viewModel = viewModel, onClose = { showSettings = false })
                }
            }
        }
    }
}

@Composable
private fun AnimatedCardVisibility(
    visible: Boolean,
    delayMillis: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )
    val cardOffsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(500, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = cardAlpha
            translationY = cardOffsetY
        }
    ) {
        content()
    }
}

// ==================== Header ====================

@Composable
private fun MobileHeaderSection(
    platform: Platform,
    state: AppUiState,
    onOpenSettings: () -> Unit,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    HazeSurface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // App icon badge
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(Res.drawable.icon_pip),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Column {
                    // Animated gradient title
                    val color1 = MaterialTheme.colorScheme.primary
                    val color2 = MaterialTheme.colorScheme.tertiary
                    val infiniteTransition = rememberInfiniteTransition(label = "MobileTitleColor")
    val animatedColor by infiniteTransition.animateColor(
                        initialValue = color1,
                        targetValue = color2,
                        animationSpec = infiniteRepeatable(
                            animation = tween(4000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "Color"
                    )
                    
                    Text(
                        stringResource(Res.string.appName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = animatedColor
                    )
                    Text(
                        "${stringResource(Res.string.ipLabel)}${platform.ipAddress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
    val settingsInteractionSource = remember { MutableInteractionSource() }
    val isSettingsPressed by settingsInteractionSource.collectIsPressedAsState()
    val settingsScale by animateFloatAsState(
                targetValue = if (isSettingsPressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
            )
            
            IconButton(
                onClick = onOpenSettings,
                interactionSource = settingsInteractionSource,
                modifier = Modifier.size(32.dp).scale(settingsScale)
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = stringResource(Res.string.settingsTitle),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================== Connection Config ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionConfigCard(
    state: AppUiState,
    viewModel: MainViewModel,
    isClient: Boolean,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    HazeSurface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mode label
            Text(
                stringResource(Res.string.connectionModeLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Mode selector - icon style like Desktop
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val modes = listOf(
                    ConnectionMode.Wifi to (stringResource(Res.string.modeWifi) to Icons.Rounded.Wifi),
                    ConnectionMode.Usb to (stringResource(Res.string.modeUsb) to Icons.Rounded.Usb)
                )

                modes.forEach { (mode, info) ->
                    val (label, icon) = info
                    val isSelected = state.mode == mode

                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        animationSpec = tween(200)
                    )
    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(bgColor)
                            .hoverable(interactionSource = remember { MutableInteractionSource() })
                            .clickable { viewModel.setMode(mode) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(icon, null, tint = contentColor, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Discovered devices list (WiFi mode only, always visible during connection)
            if (isClient && state.mode == ConnectionMode.Wifi) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Header row: label + refresh button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isDiscovering && state.discoveredDevices.isEmpty()) {
                            Icon(
                                Icons.Rounded.Dns, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(Res.string.scanningForDevices),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Text(
                                stringResource(Res.string.discoveredDevicesLabel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.restartDiscovery() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val rotation by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                )
                            )
                            Icon(
                                Icons.Filled.Refresh, null,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = if (state.isDiscovering) rotation else 0f },
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Device list
                    state.discoveredDevices.forEach { device ->
                        val isSelected = state.ipAddress == device.hostAddress &&
                                state.port == device.port.toString()
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.selectDiscoveredDevice(device)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.name, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${device.hostAddress}:${device.port}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // IP/Port input
            AnimatedVisibility(
                visible = isClient && state.mode != ConnectionMode.Usb,
                enter = fadeIn(tween(300)) + expandVertically(),
                exit = fadeOut(tween(200)) + shrinkVertically()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isClient && state.mode != ConnectionMode.Usb) {
                        ShardTextField(
                            value = state.ipAddress,
                            onValueChange = { viewModel.setIp(it) },
                            label = stringResource(Res.string.targetIpLabel),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                    ShardTextField(
                        value = state.port,
                        onValueChange = { viewModel.setPort(it) },
                        label = stringResource(Res.string.portLabel),
                        modifier = if (isClient && state.mode != ConnectionMode.Usb)
                            Modifier.width(100.dp) else Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ==================== Main Control ====================

@Composable
private fun MainControlCard(
    state: AppUiState,
    viewModel: MainViewModel,
    audioLevel: Float,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    val isRunning = state.streamState == StreamState.Streaming
    val isConnecting = state.streamState == StreamState.Connecting

    HazeSurface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Status indicator at top
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status icon
                val statusColor by animateColorAsState(
                    targetValue = when (state.streamState) {
                        StreamState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
                        StreamState.Connecting -> MaterialTheme.colorScheme.tertiary
                        StreamState.Streaming -> MaterialTheme.colorScheme.primary
                        StreamState.Error -> MaterialTheme.colorScheme.error
                    },
                    animationSpec = tween(300)
                )

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = statusColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when (state.streamState) {
                                StreamState.Idle -> Icons.Rounded.Info
                                StreamState.Connecting -> Icons.Rounded.HourglassTop
                                StreamState.Streaming -> Icons.Rounded.CheckCircle
                                StreamState.Error -> Icons.Rounded.Error
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                // Status text
                val statusText = when (state.streamState) {
                    StreamState.Idle -> stringResource(Res.string.clickToStart)
                    StreamState.Connecting -> stringResource(Res.string.statusConnecting)
                    StreamState.Streaming -> stringResource(Res.string.statusStreaming)
                    StreamState.Error -> state.errorMessage ?: stringResource(Res.string.statusError)
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (isRunning) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = statusColor
                        ) {
                            Text(
                                "LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                // Error message
                AnimatedVisibility(
                    visible = state.streamState == StreamState.Error && state.errorMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    if (state.errorMessage != null) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Text(
                                state.errorMessage ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp),
                                maxLines = 3,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Audio visualizer
            if (isRunning) {
                MobileAudioVisualizer(
                    modifier = Modifier.size(240.dp),
                    audioLevel = audioLevel,
                    color = MaterialTheme.colorScheme.primary,
                    style = state.visualizerStyle
                )
            }
            
            // Connecting animation
            if (isConnecting) {
                ConnectingAnimation(
                    modifier = Modifier.size(200.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    isDesktop = false
                )
            }
            
            // Main button
            MobileMainButton(
                isRunning = isRunning,
                isConnecting = isConnecting,
                viewModel = viewModel)
        }
    }
}

// ==================== Bottom Bar ====================

@Composable
private fun MobileBottomBar(
    state: AppUiState,
    viewModel: MainViewModel,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    var showPluginPopup by remember { mutableStateOf(false) }
    var activePluginWindow by remember { mutableStateOf<String?>(null) }
    
    HazeSurface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobileMuteButton(
                    isMuted = state.isMuted,
                    onToggle = { viewModel.toggleMute() })
    val enabledPlugins = state.plugins.filter { it.isEnabled }
    val pluginInteractionSource = remember { MutableInteractionSource() }
    val isPluginPressed by pluginInteractionSource.collectIsPressedAsState()
    val pluginScale by animateFloatAsState(
                    targetValue = if (isPluginPressed) 0.9f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                )
    val pluginBgColor by animateColorAsState(
                    targetValue = if (enabledPlugins.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    animationSpec = tween(200)
                )
    val pluginContentColor by animateColorAsState(
                    targetValue = if (enabledPlugins.isNotEmpty()) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(200)
                )
                
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = pluginBgColor,
                    modifier = Modifier.scale(pluginScale).clickable(pluginInteractionSource, null) { showPluginPopup = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Extension,
                            contentDescription = stringResource(Res.string.pluginsSection),
                            tint = pluginContentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        if (enabledPlugins.isNotEmpty()) {
                            Text(
                                enabledPlugins.size.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = pluginContentColor
                            )
                        }
                    }
                }
            }
    val dotColor by animateColorAsState(
                targetValue = when (state.streamState) {
                    StreamState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    StreamState.Connecting -> MaterialTheme.colorScheme.tertiary
                    StreamState.Streaming -> MaterialTheme.colorScheme.primary
                    StreamState.Error -> MaterialTheme.colorScheme.error
                },
                animationSpec = tween(300)
            )
    val dotPulse = if (state.streamState == StreamState.Streaming)
                rememberPulseAnimation(0.8f, 1.2f, 1200) else 1f
            
            Surface(
                shape = CircleShape,
                color = dotColor,
                modifier = Modifier.size(8.dp).scale(dotPulse)
            ) {}
        }
    }
    
    // 显示插件窗口
    activePluginWindow?.let { pluginId ->
        OpenPluginWindow(
            pluginId = pluginId,
            viewModel = viewModel,
            onClose = { activePluginWindow = null }
        )
    }

    if (showPluginPopup) {
        PluginListPopup(
            plugins = state.plugins,
            onDismiss = { showPluginPopup = false },
            onPluginClick = { plugin ->
                showPluginPopup = false
            },
            onOpenPluginWindow = { pluginId ->
                activePluginWindow = pluginId
                showPluginPopup = false
            },
            onOpenSettings = {
                showPluginPopup = false
            },
            viewModel = viewModel
        )
    }
}

@Composable
private fun MobileMuteButton(
    isMuted: Boolean,
    onToggle: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
    )
    val bgColor by animateColorAsState(
        targetValue = if (isMuted) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(200)
    )
    val contentColor by animateColorAsState(
        targetValue = if (isMuted) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200)
    )
    
    Surface(
        shape = MaterialTheme.shapes.small,
        color = bgColor,
        modifier = Modifier.scale(scale).clickable(interactionSource, null) { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                if (isMuted) stringResource(Res.string.unmuteLabel) else stringResource(Res.string.muteLabel),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor
            )
        }
    }
}

// ==================== Audio Visualizers ====================

@Composable
private fun MobileAudioVisualizer(
    modifier: Modifier = Modifier,
    audioLevel: Float,
    color: Color,
    style: VisualizerStyle = VisualizerStyle.Ripple
) {
    AudioVisualizer(
        modifier = modifier,
        audioLevel = audioLevel,
        color = color,
        style = style,
        isDesktop = false
    )
}

// Visualizer implementations moved to AudioVisualizers.kt













// ==================== Main Button ====================

@Composable
private fun MobileMainButton(
    isRunning: Boolean,
    isConnecting: Boolean,
    viewModel: MainViewModel
) {
    val buttonSize by animateDpAsState(
        targetValue = if (isRunning) 100.dp else 80.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    val buttonColor by animateColorAsState(
        targetValue = when {
            isRunning -> MaterialTheme.colorScheme.error
            isConnecting -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400, easing = EasingFunctions.EaseInOutCubic)
    )
    val infiniteTransition = rememberInfiniteTransition(label = "MobileButton")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing)
        ),
        label = "MobileSpinner"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EasingFunctions.EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BtnGlow"
    )
    val pulseScale = if (isRunning) rememberPulseAnimation(0.96f, 1.04f, 900) else 1f
    
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(buttonSize + 24.dp)
            .graphicsLayer {
                scaleX = pressScale * pulseScale
                scaleY = pressScale * pulseScale
            }
    ) {
        // Glow ring behind button
        if (isRunning) {
            Canvas(modifier = Modifier.size(buttonSize + 20.dp)) {
                drawCircle(buttonColor.copy(alpha = glowAlpha * 0.35f), size.width / 2)
            }
        }
        
        if (isRunning || isConnecting) {
            Box(
                modifier = Modifier
                    .size(buttonSize + 24.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    buttonColor.copy(alpha = 0.25f),
                                    buttonColor.copy(alpha = 0f)
                                ),
                                center = Offset(size.width / 2, size.height / 2),
                                radius = size.width / 2
                            ),
                            radius = size.width / 2
                        )
                    }
            )
        }
        
        FloatingActionButton(
            onClick = {
                if (isRunning || isConnecting) {
                    viewModel.stopStream()
                } else {
                    viewModel.startStream()
                }
            },
            interactionSource = interactionSource,
            containerColor = buttonColor,
            modifier = Modifier.size(buttonSize),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (isPressed) 2.dp else 8.dp,
                pressedElevation = 2.dp
            )
        ) {
            if (isConnecting) {
                Icon(
                    Icons.Filled.Refresh,
                    stringResource(Res.string.statusConnecting),
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer { rotationZ = angle }
                )
            } else {
                Icon(
                    if (isRunning) Icons.Filled.LinkOff else Icons.Filled.Link,
                    contentDescription = if (isRunning) stringResource(Res.string.stop) else stringResource(Res.string.start),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

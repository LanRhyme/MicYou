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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.icon_bluetooth
import micyou.composeapp.generated.resources.icon_home_wifi
import micyou.composeapp.generated.resources.icon_pip
import micyou.composeapp.generated.resources.icon_settings
import micyou.composeapp.generated.resources.icon_usb
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHome(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val platform = remember { getPlatform() }
    val isClient = platform.type == PlatformType.Android
    val strings = LocalAppStrings.current
    val snackbarHostState = remember { SnackbarHostState() }
    val isDarkTheme = isDarkThemeActive(state.themeMode)
    val forcePureBlackBackground = state.oledPureBlack && isDarkTheme
    
    var showSettings by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    
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

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                DesktopSettings(viewModel = viewModel, onClose = { showSettings = false })
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
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
                        strings = strings,
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
                        strings = strings,
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
                        strings = strings,
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
                        strings = strings,
                        cardOpacity = state.backgroundSettings.cardOpacity,
                        hazeState = hazeState
                    )
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
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = 0.001f
        )
    )
    val cardOffsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(500, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = cardAlpha
            this.scaleX = cardScale
            this.scaleY = cardScale
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
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // App icon badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(Res.drawable.icon_pip),
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
                        strings.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = animatedColor
                    )
                    Text(
                        "${strings.ipLabel}${platform.ipAddress}",
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
                    painterResource(Res.drawable.icon_settings),
                    contentDescription = strings.settingsTitle,
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
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mode label
            Text(
                strings.connectionModeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Mode selector - icon style like Desktop
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val modes = listOf(
                    ConnectionMode.Wifi to (strings.modeWifi to painterResource(Res.drawable.icon_home_wifi)),
                    ConnectionMode.Bluetooth to (strings.modeBluetooth to painterResource(Res.drawable.icon_bluetooth)),
                    ConnectionMode.Usb to (strings.modeUsb to painterResource(Res.drawable.icon_usb))
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
                    
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = bgColor,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable { viewModel.setMode(mode) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(icon, null, tint = contentColor, modifier = Modifier.size(18.dp))
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

            // IP/Port input
            AnimatedVisibility(
                visible = isClient && state.mode != ConnectionMode.Usb || state.mode != ConnectionMode.Bluetooth,
                enter = fadeIn(tween(300)) + expandVertically(),
                exit = fadeOut(tween(200)) + shrinkVertically()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isClient && state.mode != ConnectionMode.Usb) {
                        ShardTextField(
                            value = when (state.mode) {
                                ConnectionMode.Bluetooth -> state.bluetoothAddress
                                else -> state.ipAddress
                            },
                            onValueChange = { viewModel.setIp(it) },
                            label = when (state.mode) {
                                ConnectionMode.Bluetooth -> strings.bluetoothAddressLabel
                                else -> strings.targetIpLabel
                            },
                            modifier = if (state.mode == ConnectionMode.Bluetooth) Modifier.fillMaxWidth()
                            else Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (state.mode != ConnectionMode.Bluetooth) {
                        ShardTextField(
                            value = state.port,
                            onValueChange = { viewModel.setPort(it) },
                            label = strings.portLabel,
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
}

// ==================== Main Control ====================

@Composable
private fun MainControlCard(
    state: AppUiState,
    viewModel: MainViewModel,
    audioLevel: Float,
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    val isRunning = state.streamState == StreamState.Streaming
    val isConnecting = state.streamState == StreamState.Connecting

    HazeSurface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f * cardOpacity),
        hazeColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f * cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Status indicator at top
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp),
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
                    shape = RoundedCornerShape(12.dp),
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
                    StreamState.Idle -> strings.clickToStart
                    StreamState.Connecting -> strings.statusConnecting
                    StreamState.Streaming -> strings.statusStreaming
                    StreamState.Error -> state.errorMessage ?: strings.statusError
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
                            shape = RoundedCornerShape(3.dp),
                            color = statusColor
                        ) {
                            Text(
                                "LIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
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
                            shape = RoundedCornerShape(8.dp),
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
                MobileConnectingAnimation(
                    modifier = Modifier.size(200.dp),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            
            // Main button
            MobileMainButton(
                isRunning = isRunning,
                isConnecting = isConnecting,
                viewModel = viewModel,
                strings = strings
            )
        }
    }
}

// ==================== Bottom Bar ====================

@Composable
private fun MobileBottomBar(
    state: AppUiState,
    viewModel: MainViewModel,
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null
) {
    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Mute button (compact, like Desktop BottomBar)
            MobileMuteButton(
                isMuted = state.isMuted,
                onToggle = { viewModel.toggleMute() },
                strings = strings
            )
            
            // Stream state indicator dot
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
}

@Composable
private fun MobileMuteButton(
    isMuted: Boolean,
    onToggle: () -> Unit,
    strings: AppStrings
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
        shape = RoundedCornerShape(10.dp),
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
                if (isMuted) strings.unmuteLabel else strings.muteLabel,
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
    val safeAudioLevel = audioLevel.coerceIn(0f, 1f)
    val breathScale = rememberBreathAnimation(0.97f, 1.03f, 1800)
    val wavePhase = rememberWaveAnimation(phaseOffset = 0f, durationMillis = 2500)
    val glowAlpha = rememberGlowAnimation(0.2f, 0.5f, 2000)
    
    when (style) {
        VisualizerStyle.VolumeRing -> MobileVolumeRingVisualizer(modifier, safeAudioLevel, color)
        VisualizerStyle.Ripple -> MobileRippleVisualizer(modifier, safeAudioLevel, color, breathScale, wavePhase)
        VisualizerStyle.Bars -> MobileBarsVisualizer(modifier, safeAudioLevel, color, wavePhase)
        VisualizerStyle.Wave -> MobileWaveVisualizer(modifier, safeAudioLevel, color, wavePhase)
        VisualizerStyle.Glow -> MobileGlowVisualizer(modifier, safeAudioLevel, color, glowAlpha, breathScale)
        VisualizerStyle.Particles -> MobileParticlesVisualizer(modifier, safeAudioLevel, color, wavePhase)
    }
}

@Composable
private fun MobileVolumeRingVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color
) {
    val animatedLevel by animateFloatAsState(
        targetValue = audioLevel,
        animationSpec = tween(100, easing = LinearEasing),
        label = "VolumeLevel"
    )
    
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2 * 0.85f
        val strokeWidth = 8.dp.toPx()
        
        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = baseRadius,
            center = center,
            style = Stroke(width = strokeWidth)
        )
        
        val sweepAngle = 360f * animatedLevel
        val startAngle = -90f
        
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
            size = androidx.compose.ui.geometry.Size(baseRadius * 2, baseRadius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        
        if (audioLevel > 0.05f) {
            val endAngleRad = Math.toRadians((startAngle + sweepAngle).toDouble()).toFloat()
            val dotX = center.x + baseRadius * cos(endAngleRad)
            val dotY = center.y + baseRadius * sin(endAngleRad)
            
            drawCircle(
                color = color.copy(alpha = 0.9f),
                radius = strokeWidth * 0.8f,
                center = Offset(dotX, dotY)
            )
        }
        
        val tickCount = 60
        for (i in 0 until tickCount) {
            val tickAngle = -90f + (i.toFloat() / tickCount) * 360f
            val tickAngleRad = Math.toRadians(tickAngle.toDouble()).toFloat()
            val tickProgress = i.toFloat() / tickCount
            
            val innerRadius = baseRadius - strokeWidth * 0.5f
            val outerRadius = baseRadius + strokeWidth * 0.5f
            
            val tickAlpha = if (tickProgress <= animatedLevel) 0.4f else 0.1f
            val tickLength = if (i % 5 == 0) 6.dp.toPx() else 3.dp.toPx()
            
            val startX = center.x + innerRadius * cos(tickAngleRad)
            val startY = center.y + innerRadius * sin(tickAngleRad)
            val endX = center.x + (outerRadius + tickLength) * cos(tickAngleRad)
            val endY = center.y + (outerRadius + tickLength) * sin(tickAngleRad)
            
            drawLine(
                color = color.copy(alpha = tickAlpha),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = if (i % 5 == 0) 2.dp.toPx() else 1.dp.toPx()
            )
        }
        
        val glowRadius = baseRadius * 0.6f * animatedLevel
        if (glowRadius > 0) {
            drawCircle(
                color = color.copy(alpha = 0.1f * animatedLevel),
                radius = glowRadius,
                center = center
            )
        }
    }
}

@Composable
private fun MobileRippleVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    breathScale: Float,
    wavePhase: Float
) {
    Canvas(modifier = modifier.scale(breathScale)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        for (i in 0..4) {
            val waveRadius = baseRadius * (0.5f + i * 0.15f * audioLevel)
            val alpha = (0.35f - i * 0.07f) * audioLevel
            
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = waveRadius,
                center = center,
                style = Stroke(width = (4 - i * 0.7f).dp.toPx())
            )
        }
        
        val barCount = 48
        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f + wavePhase
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            
            val dynamicLevel = audioLevel * (0.4f + 0.6f * sin(angle * 0.08f + wavePhase * 0.025f))
            val barHeight = baseRadius * 0.18f * dynamicLevel
            
            val innerRadius = baseRadius * 0.45f
            val startX = center.x + innerRadius * cos(radians)
            val startY = center.y + innerRadius * sin(radians)
            val endX = center.x + (innerRadius + barHeight) * cos(radians)
            val endY = center.y + (innerRadius + barHeight) * sin(radians)
            
            drawLine(
                color = color.copy(alpha = 0.5f * audioLevel),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun MobileBarsVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    wavePhase: Float
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        val barCount = 48
        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            
            val normalizedAngle = (angle + wavePhase) % 360f
            val dynamicLevel = audioLevel * (0.3f + 0.7f * abs(sin(normalizedAngle * 0.03f + wavePhase * 0.015f)))
            val barHeight = baseRadius * 0.35f * dynamicLevel
            
            val innerRadius = baseRadius * 0.35f
            val barWidth = (2.5f * (1f + dynamicLevel * 0.5f)).dp.toPx()
            
            drawLine(
                color = color.copy(alpha = (0.4f + dynamicLevel * 0.5f).coerceIn(0f, 1f)),
                start = Offset(center.x + innerRadius * cos(radians), center.y + innerRadius * sin(radians)),
                end = Offset(center.x + (innerRadius + barHeight) * cos(radians), center.y + (innerRadius + barHeight) * sin(radians)),
                strokeWidth = barWidth, cap = StrokeCap.Round
            )
        }
        
        val innerGlowRadius = baseRadius * 0.3f
        drawCircle(
            color.copy(alpha = audioLevel * 0.15f),
            innerGlowRadius,
            center
        )
    }
}

@Composable
private fun MobileWaveVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    wavePhase: Float
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        for (waveIndex in 0..2) {
            val waveRadius = baseRadius * (0.4f + waveIndex * 0.15f)
            val waveAmplitude = baseRadius * 0.08f * audioLevel * (1f - waveIndex * 0.25f)
            
            val path = androidx.compose.ui.graphics.Path()
            val segments = 72
            
            for (i in 0..segments) {
                val angle = (i.toFloat() / segments) * 360f
                val radians = Math.toRadians(angle.toDouble()).toFloat()
                
                val waveOffset = waveAmplitude * sin(angle * 0.1f + wavePhase * 0.05f + waveIndex * 1.5f)
                val r = waveRadius + waveOffset
                
                val x = center.x + r * cos(radians)
                val y = center.y + r * sin(radians)
                
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            
            drawPath(
                path = path,
                color = color.copy(alpha = (0.5f - waveIndex * 0.12f) * audioLevel),
                style = Stroke(width = (3f - waveIndex * 0.5f).dp.toPx())
            )
        }
        
        drawCircle(
            color.copy(alpha = audioLevel * 0.2f),
            baseRadius * 0.25f,
            center
        )
    }
}

@Composable
private fun MobileGlowVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    glowAlpha: Float,
    breathScale: Float
) {
    Canvas(modifier = modifier.scale(breathScale)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        repeat(12) { i ->
            val progress = i.toFloat() / 12
            val glowRadius = baseRadius * (0.2f + progress * 0.6f) * (1f + audioLevel * 0.3f)
            val alpha = (glowAlpha * (1f - progress * 0.8f) * audioLevel).coerceIn(0f, 0.35f)
            drawCircle(color.copy(alpha = alpha), glowRadius, center)
        }
        
        val coreRadius = baseRadius * 0.15f * (1f + audioLevel * 0.5f)
        drawCircle(color.copy(alpha = 0.6f * audioLevel), coreRadius, center)
        
        val rayCount = 8
        for (i in 0 until rayCount) {
            val angle = (i.toFloat() / rayCount) * 360f
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            val rayLength = baseRadius * 0.4f * audioLevel
            
            drawLine(
                color = color.copy(alpha = 0.3f * audioLevel),
                start = center,
                end = Offset(center.x + rayLength * cos(radians), center.y + rayLength * sin(radians)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun MobileParticlesVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    wavePhase: Float
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        val particleCount = 36
        for (i in 0 until particleCount) {
            val baseAngle = (i.toFloat() / particleCount) * 360f
            val angleOffset = sin(wavePhase * 0.02f + i * 0.5f) * 15f
            val angle = baseAngle + angleOffset
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            
            val distanceVariation = sin(wavePhase * 0.03f + i * 0.3f) * 0.3f
            val baseDistance = baseRadius * (0.35f + distanceVariation)
            val distance = baseDistance * (0.5f + audioLevel * 0.8f)
            
            val x = center.x + distance * cos(radians)
            val y = center.y + distance * sin(radians)
            
            val particleSize = (3f + audioLevel * 4f * abs(sin(wavePhase * 0.02f + i))).dp.toPx()
            val alpha = (0.3f + audioLevel * 0.5f).coerceIn(0f, 1f)
            
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = particleSize / 2,
                center = Offset(x, y)
            )
            
            val trailLength = baseRadius * 0.1f * audioLevel
            drawLine(
                color = color.copy(alpha = alpha * 0.5f),
                start = Offset(x, y),
                end = Offset(
                    x - trailLength * cos(radians),
                    y - trailLength * sin(radians)
                ),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
            )
        }
        
        drawCircle(
            color.copy(alpha = audioLevel * 0.15f),
            baseRadius * 0.2f,
            center
        )
    }
}

// ==================== Connecting Animation ====================

@Composable
private fun MobileConnectingAnimation(
    modifier: Modifier = Modifier,
    color: Color
) {
    val rotation = rememberRotationAnimation(2500)
    val pulse = rememberPulseAnimation(0.92f, 1.08f, 1200)
    
    Canvas(modifier = modifier.scale(pulse)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2
        
        for (i in 0..2) {
            val arcAngle = rotation + i * 120f
            val sweepAngle = 50f + 30f * sin(rotation * 0.025f)
            
            drawArc(
                color = color.copy(alpha = 0.5f - i * 0.12f),
                startAngle = arcAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius * (0.45f + i * 0.18f), center.y - radius * (0.45f + i * 0.18f)),
                size = Size(radius * 2 * (0.45f + i * 0.18f), radius * 2 * (0.45f + i * 0.18f)),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

// ==================== Main Button ====================

@Composable
private fun MobileMainButton(
    isRunning: Boolean,
    isConnecting: Boolean,
    viewModel: MainViewModel,
    strings: AppStrings
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
            contentColor = Color.White,
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
                    strings.statusConnecting,
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer { rotationZ = angle }
                )
            } else {
                Icon(
                    if (isRunning) Icons.Filled.LinkOff else Icons.Filled.Link,
                    contentDescription = if (isRunning) strings.stop else strings.start,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

package com.lanrhyme.micyou

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.animation.EasingFunctions
import com.lanrhyme.micyou.animation.rememberBreathAnimation
import com.lanrhyme.micyou.animation.rememberGlowAnimation
import com.lanrhyme.micyou.animation.rememberPulseAnimation
import com.lanrhyme.micyou.animation.rememberRotationAnimation
import com.lanrhyme.micyou.animation.rememberWaveAnimation
import kotlinx.coroutines.delay
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
    
    var showSettings by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    
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
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            strings.appName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        AnimatedVisibility(
                            visible = contentVisible,
                            enter = fadeIn(tween(300)) + slideInVertically(
                                initialOffsetY = { -10 },
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                            )
                        ) {
                            Text(
                                "${strings.ipLabel}${platform.ipAddress}", 
                                style = MaterialTheme.typography.bodySmall, 
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    val settingsInteractionSource = remember { MutableInteractionSource() }
                    val isSettingsPressed by settingsInteractionSource.collectIsPressedAsState()
                    val settingsScale by animateFloatAsState(
                        targetValue = if (isSettingsPressed) 0.85f else 1f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                    )
                    
                    IconButton(
                        onClick = { showSettings = true },
                        interactionSource = settingsInteractionSource,
                        modifier = Modifier.scale(settingsScale)
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = strings.settingsTitle)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AnimatedCardVisibility(
                visible = contentVisible,
                delayMillis = 100
            ) {
                ConnectionConfigCard(
                    state = state,
                    viewModel = viewModel,
                    isClient = isClient,
                    strings = strings
                )
            }

            AnimatedCardVisibility(
                visible = contentVisible,
                delayMillis = 200
            ) {
                MuteCard(
                    state = state,
                    viewModel = viewModel,
                    strings = strings
                )
            }
            
            AnimatedCardVisibility(
                visible = contentVisible,
                delayMillis = 300,
                modifier = Modifier.weight(1f)
            ) {
                MainControlCard(
                    state = state,
                    viewModel = viewModel,
                    audioLevel = audioLevel,
                    strings = strings
                )
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
    var localVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(visible) {
        if (visible) {
            delay(delayMillis.toLong())
            localVisible = true
        }
    }
    
    AnimatedVisibility(
        visible = localVisible,
        enter = fadeIn(
            animationSpec = tween(400, easing = EasingFunctions.EaseOutExpo)
        ) + slideInVertically(
            initialOffsetY = { 30 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + scaleIn(
            initialScale = 0.9f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ),
        exit = fadeOut(tween(200)) + slideOutVertically { 20 } + scaleOut(targetScale = 0.95f),
        modifier = modifier
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionConfigCard(
    state: AppUiState,
    viewModel: MainViewModel,
    isClient: Boolean,
    strings: AppStrings
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    strings.connectionModeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val modes = listOf(
                        ConnectionMode.Wifi to strings.modeWifi,
                        ConnectionMode.Bluetooth to strings.modeBluetooth,
                        ConnectionMode.Usb to strings.modeUsb
                    )
                    
                    modes.forEachIndexed { index, (mode, label) ->
                        var chipVisible by remember { mutableStateOf(false) }
                        
                        LaunchedEffect(Unit) {
                            delay(100L + index * 50L)
                            chipVisible = true
                        }
                        
                        AnimatedVisibility(
                            visible = chipVisible,
                            enter = fadeIn(tween(200)) + slideInHorizontally(
                                initialOffsetX = { -20 },
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                            ) + scaleIn(initialScale = 0.8f),
                            exit = fadeOut(tween(150)) + slideOutHorizontally { 20 } + scaleOut(targetScale = 0.8f),
                            modifier = Modifier.weight(1f)
                        ) {
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val chipScale by animateFloatAsState(
                                targetValue = if (isPressed) 0.92f else 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                            )
                            
                            FilterChip(
                                selected = state.mode == mode,
                                onClick = { viewModel.setMode(mode) },
                                interactionSource = interactionSource,
                                label = { Text(label) },
                                leadingIcon = { 
                                    if (state.mode == mode) {
                                        Icon(
                                            Icons.Filled.Check,
                                            null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                modifier = Modifier.scale(chipScale),
                                shape = CircleShape
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isClient && state.mode != ConnectionMode.Usb || state.mode != ConnectionMode.Bluetooth,
                enter = fadeIn(tween(300)) + slideInVertically(
                    initialOffsetY = { 15 },
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                ) + scaleIn(initialScale = 0.95f),
                exit = fadeOut(tween(200)) + slideOutVertically { 10 } + scaleOut(targetScale = 0.95f)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isClient && state.mode != ConnectionMode.Usb) {
                        OutlinedTextField(
                            value = when (state.mode) {
                                ConnectionMode.Bluetooth -> state.bluetoothAddress
                                else -> state.ipAddress
                            },
                            onValueChange = { viewModel.setIp(it) },
                            label = {
                                Text(
                                    when (state.mode) {
                                        ConnectionMode.Bluetooth -> strings.bluetoothAddressLabel
                                        else -> strings.targetIpLabel
                                    }
                                )
                            },
                            modifier = if (state.mode == ConnectionMode.Bluetooth) Modifier.fillMaxWidth() else Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (state.mode != ConnectionMode.Bluetooth) {
                        OutlinedTextField(
                            value = state.port,
                            onValueChange = { viewModel.setPort(it) },
                            label = { Text(strings.portLabel) },
                            modifier = if (isClient && state.mode != ConnectionMode.Usb) Modifier.width(100.dp) else Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MuteCard(
    state: AppUiState,
    viewModel: MainViewModel,
    strings: AppStrings
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
    )
    
    val cardColor by animateColorAsState(
        targetValue = if (state.isMuted)
            MaterialTheme.colorScheme.errorContainer
        else
            MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(300, easing = EasingFunctions.EaseInOutCubic)
    )
    
    Card(
        onClick = { viewModel.toggleMute() },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconScale by animateFloatAsState(
                targetValue = if (state.isMuted) 1.1f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            )
            
            Icon(
                if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(28.dp).scale(iconScale),
                tint = if (state.isMuted)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = if (state.isMuted) strings.unmuteLabel else strings.muteLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (state.isMuted)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MainControlCard(
    state: AppUiState,
    viewModel: MainViewModel,
    audioLevel: Float,
    strings: AppStrings
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(32.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val isRunning = state.streamState == StreamState.Streaming
            val isConnecting = state.streamState == StreamState.Connecting
            
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
            ) {
                val (statusColor, statusText) = when(state.streamState) {
                    StreamState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to strings.clickToStart
                    StreamState.Connecting -> MaterialTheme.colorScheme.tertiary to strings.statusConnecting
                    StreamState.Streaming -> MaterialTheme.colorScheme.primary to strings.statusStreaming
                    StreamState.Error -> MaterialTheme.colorScheme.error to (state.errorMessage ?: strings.statusError)
                }
                
                val statusScale = rememberPulseAnimation(0.97f, 1.03f, 1500)
                
                Surface(
                    color = statusColor.copy(alpha = 0.1f),
                    contentColor = statusColor,
                    shape = CircleShape,
                    modifier = Modifier.scale(statusScale)
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (isRunning) {
                MobileAudioVisualizer(
                    modifier = Modifier.size(240.dp),
                    audioLevel = audioLevel,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            if (isConnecting) {
                MobileConnectingAnimation(
                    modifier = Modifier.size(200.dp),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            
            MobileMainButton(
                isRunning = isRunning,
                isConnecting = isConnecting,
                viewModel = viewModel,
                strings = strings
            )
        }
    }
}

@Composable
private fun MobileAudioVisualizer(
    modifier: Modifier = Modifier,
    audioLevel: Float,
    color: Color
) {
    val safeAudioLevel = audioLevel.coerceIn(0f, 1f)
    val breathScale = rememberBreathAnimation(0.97f, 1.03f, 1800)
    val wavePhase = rememberWaveAnimation(phaseOffset = 0f, durationMillis = 2500)
    
    Canvas(modifier = modifier.scale(breathScale)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        for (i in 0..4) {
            val waveRadius = baseRadius * (0.5f + i * 0.15f * safeAudioLevel)
            val alpha = (0.35f - i * 0.07f) * safeAudioLevel
            
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
            
            val dynamicLevel = safeAudioLevel * (0.4f + 0.6f * sin(angle * 0.08f + wavePhase * 0.025f))
            val barHeight = baseRadius * 0.18f * dynamicLevel
            
            val innerRadius = baseRadius * 0.45f
            val startX = center.x + innerRadius * cos(radians)
            val startY = center.y + innerRadius * sin(radians)
            val endX = center.x + (innerRadius + barHeight) * cos(radians)
            val endY = center.y + (innerRadius + barHeight) * sin(radians)
            
            drawLine(
                color = color.copy(alpha = 0.5f * safeAudioLevel),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

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

@Composable
private fun MobileMainButton(
    isRunning: Boolean,
    isConnecting: Boolean,
    viewModel: MainViewModel,
    strings: AppStrings
) {
    val buttonSize by animateDpAsState(
        targetValue = if (isRunning) 115.dp else 95.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    
    val buttonColor by animateColorAsState(
        targetValue = when {
            isRunning -> MaterialTheme.colorScheme.error
            isConnecting -> MaterialTheme.colorScheme.secondary
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
            .size(buttonSize + 32.dp)
            .graphicsLayer {
                scaleX = pressScale * pulseScale
                scaleY = pressScale * pulseScale
            }
    ) {
        if (isRunning || isConnecting) {
            Box(
                modifier = Modifier
                    .size(buttonSize + 32.dp)
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
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(buttonSize),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (isPressed) 2.dp else 10.dp,
                pressedElevation = 2.dp
            )
        ) {
            if (isConnecting) {
                Icon(
                    Icons.Filled.Refresh,
                    strings.statusConnecting,
                    modifier = Modifier
                        .size(42.dp)
                        .graphicsLayer { rotationZ = angle }
                )
            } else {
                Icon(
                    if (isRunning) Icons.Filled.LinkOff else Icons.Filled.Link,
                    contentDescription = if (isRunning) strings.stop else strings.start,
                    modifier = Modifier.size(42.dp)
                )
            }
        }
    }
}

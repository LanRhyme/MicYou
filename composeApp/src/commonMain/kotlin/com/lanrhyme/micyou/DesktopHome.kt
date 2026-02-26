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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.animation.AnimationSpecs
import com.lanrhyme.micyou.animation.EasingFunctions
import com.lanrhyme.micyou.animation.rememberBreathAnimation
import com.lanrhyme.micyou.animation.rememberGlowAnimation
import com.lanrhyme.micyou.animation.rememberPulseAnimation
import com.lanrhyme.micyou.animation.rememberRotationAnimation
import com.lanrhyme.micyou.animation.rememberWaveAnimation
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopHome(
    viewModel: MainViewModel,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onExitApp: () -> Unit,
    onHideApp: () -> Unit,
    onOpenSettings: () -> Unit,
    isBluetoothDisabled: Boolean = false
) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val platform = remember { getPlatform() }
    
    val strings = LocalAppStrings.current
    
    var visible by remember { mutableStateOf(false) }
    var cardVisible by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, easing = EasingFunctions.EaseOutExpo)
    )
    
    LaunchedEffect(Unit) {
        visible = true
        delay(100)
        cardVisible = true
    }

    LaunchedEffect(isBluetoothDisabled, state.mode) {
        if (isBluetoothDisabled && state.mode == ConnectionMode.Bluetooth) {
            viewModel.setMode(ConnectionMode.Wifi)
        }
    }

    if (state.installMessage != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(strings.systemConfigTitle) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(state.installMessage ?: "")
                }
            },
            confirmButton = {}
        )
    }

    if (state.showFirewallDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFirewallDialog() },
            title = { Text(strings.firewallTitle) },
            text = { Text(strings.firewallMessage.replace("%d", state.pendingFirewallPort?.toString() ?: "")) },
            confirmButton = {
                Button(onClick = { viewModel.confirmAddFirewallRule() }) {
                    Text(strings.firewallConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissFirewallDialog() }) {
                    Text(strings.firewallDismiss)
                }
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AnimatedCard(
                visible = cardVisible,
                delayMillis = 100,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                NetworkConfigCard(
                    state = state,
                    viewModel = viewModel,
                    platform = platform,
                    strings = strings,
                    isBluetoothDisabled = isBluetoothDisabled
                )
            }

            AnimatedCard(
                visible = cardVisible,
                delayMillis = 200,
                modifier = Modifier.weight(0.8f).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(22.dp)
            ) {
                ControlCenter(
                    state = state,
                    viewModel = viewModel,
                    audioLevel = audioLevel,
                    strings = strings
                )
            }

            AnimatedCard(
                visible = cardVisible,
                delayMillis = 300,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                StatusControlPanel(
                    state = state,
                    viewModel = viewModel,
                    onMinimize = onMinimize,
                    onClose = onClose,
                    onOpenSettings = onOpenSettings,
                    strings = strings
                )
            }
        }
    }
}

@Composable
private fun AnimatedCard(
    visible: Boolean,
    delayMillis: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
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

    Card(
        modifier = modifier
            .graphicsLayer {
                this.alpha = cardAlpha
                this.scaleX = cardScale
                this.scaleY = cardScale
                translationY = cardOffsetY
            },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = shape
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkConfigCard(
    state: AppUiState,
    viewModel: MainViewModel,
    platform: Platform,
    strings: AppStrings,
    isBluetoothDisabled: Boolean
) {
    var titleVisible by remember { mutableStateOf(false) }
    var fieldsVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        titleVisible = true
        delay(100)
        fieldsVisible = true
    }

    Column(
        modifier = Modifier.padding(10.dp).fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimatedVisibility(
                visible = titleVisible,
                enter = fadeIn(tween(300)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                ),
                exit = fadeOut(tween(200))
            ) {
                Text(
                    "MicYou Desktop",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            AnimatedVisibility(
                visible = titleVisible,
                enter = fadeIn(tween(300, 100)) + slideInVertically(
                    initialOffsetY = { -15 },
                    animationSpec = tween(400, easing = EasingFunctions.EaseOutExpo)
                ),
                exit = fadeOut(tween(200))
            ) {
                Box {
                    var showIpList by remember { mutableStateOf(false) }
                    val currentIps = remember(showIpList) {
                        if (showIpList) platform.ipAddresses else emptyList()
                    }

                    SelectionContainer {
                        Text(
                            "${strings.ipLabel}${platform.ipAddress}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { showIpList = true }
                        )
                    }

                    CompositionLocalProvider(
                        LocalRippleConfiguration provides RippleConfiguration(
                            rippleAlpha = RippleAlpha(0f, 0f, 0f, 0f)
                        )
                    ) {
                        DropdownMenu(
                            expanded = showIpList,
                            onDismissRequest = { showIpList = false },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            currentIps.forEach { ip ->
                                DropdownMenuItem(
                                    text = { Text(ip) },
                                    onClick = { showIpList = false }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        AnimatedVisibility(
            visible = fieldsVisible,
            enter = fadeIn(tween(400)) + slideInVertically(
                initialOffsetY = { 30 },
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut(tween(200))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        modifier = Modifier.menuAnchor().fillMaxWidth().height(60.dp),
                        readOnly = true,
                        value = when (state.mode) {
                            ConnectionMode.Wifi -> strings.modeWifi
                            ConnectionMode.Bluetooth -> strings.modeBluetooth
                            ConnectionMode.Usb -> strings.modeUsb
                        },
                        onValueChange = {},
                        label = { Text(strings.connectionModeLabel) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.modeWifi) },
                            onClick = {
                                viewModel.setMode(ConnectionMode.Wifi)
                                expanded = false
                            }
                        )
                        if (!isBluetoothDisabled) {
                            DropdownMenuItem(
                                text = { Text(strings.modeBluetooth) },
                                onClick = {
                                    viewModel.setMode(ConnectionMode.Bluetooth)
                                    expanded = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(strings.modeUsb) },
                            onClick = {
                                viewModel.setMode(ConnectionMode.Usb)
                                expanded = false
                            }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = state.mode != ConnectionMode.Bluetooth,
                    enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f),
                    exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f)
                ) {
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = { viewModel.setPort(it) },
                        label = { Text(strings.portLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlCenter(
    state: AppUiState,
    viewModel: MainViewModel,
    audioLevel: Float,
    strings: AppStrings
) {
    var contentVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        delay(200)
        contentVisible = true
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isRunning = state.streamState == StreamState.Streaming
        val isConnecting = state.streamState == StreamState.Connecting
        val visualSize = (if (maxWidth < maxHeight) maxWidth else maxHeight) * 0.85f

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isRunning) {
                AdvancedAudioVisualizer(
                    modifier = Modifier.size(visualSize),
                    audioLevel = audioLevel,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            if (isConnecting) {
                ConnectingAnimation(
                    modifier = Modifier.size(visualSize * 0.9f),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            MainControlButton(
                isRunning = isRunning,
                isConnecting = isConnecting,
                viewModel = viewModel,
                strings = strings,
                visible = contentVisible
            )
        }
    }
}

@Composable
private fun AdvancedAudioVisualizer(
    modifier: Modifier = Modifier,
    audioLevel: Float,
    color: Color
) {
    val safeAudioLevel = audioLevel.coerceIn(0f, 1f)
    val breathScale = rememberBreathAnimation(0.98f, 1.02f, 1500)
    val wavePhase = rememberWaveAnimation(phaseOffset = 0f, durationMillis = 3000)
    val glowAlpha = rememberGlowAnimation(0.2f, 0.5f, 2000)
    
    Canvas(modifier = modifier.scale(breathScale)) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        for (i in 0..3) {
            val waveRadius = baseRadius * (0.6f + i * 0.12f * safeAudioLevel)
            val alpha = (0.4f - i * 0.1f) * safeAudioLevel
            
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = waveRadius,
                center = center,
                style = Stroke(width = (3 - i * 0.5f).dp.toPx())
            )
        }
        
        val barCount = 36
        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f + wavePhase
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            
            val dynamicLevel = safeAudioLevel * (0.5f + 0.5f * sin(angle * 0.05f + wavePhase * 0.02f))
            val barHeight = baseRadius * 0.15f * dynamicLevel
            
            val innerRadius = baseRadius * 0.55f
            val startX = center.x + innerRadius * cos(radians)
            val startY = center.y + innerRadius * sin(radians)
            val endX = center.x + (innerRadius + barHeight) * cos(radians)
            val endY = center.y + (innerRadius + barHeight) * sin(radians)
            
            drawLine(
                color = color.copy(alpha = 0.6f * safeAudioLevel),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        
        val glowSteps = 8
        for (i in 0 until glowSteps) {
            val progress = i.toFloat() / glowSteps
            val glowRadius = baseRadius * 0.3f * (1f + progress * 0.5f)
            val alpha = glowAlpha * (1f - progress) * safeAudioLevel
            
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 0.3f)),
                radius = glowRadius,
                center = center
            )
        }
    }
}

@Composable
private fun ConnectingAnimation(
    modifier: Modifier = Modifier,
    color: Color
) {
    val rotation = rememberRotationAnimation(2000)
    val pulse = rememberPulseAnimation(0.9f, 1.1f, 1000)
    
    Canvas(modifier = modifier.scale(pulse)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2
        
        for (i in 0..2) {
            val arcAngle = rotation + i * 120f
            val sweepAngle = 60f + 20f * sin(rotation * 0.02f)
            
            drawArc(
                color = color.copy(alpha = 0.4f - i * 0.1f),
                startAngle = arcAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius * (0.5f + i * 0.15f), center.y - radius * (0.5f + i * 0.15f)),
                size = Size(radius * 2 * (0.5f + i * 0.15f), radius * 2 * (0.5f + i * 0.15f)),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun MainControlButton(
    isRunning: Boolean,
    isConnecting: Boolean,
    viewModel: MainViewModel,
    strings: AppStrings,
    visible: Boolean
) {
    val buttonSize by animateDpAsState(
        targetValue = if (isRunning) 76.dp else 68.dp,
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
    
    val infiniteTransition = rememberInfiniteTransition(label = "ButtonAnimation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing)
        ),
        label = "SpinnerAngle"
    )
    
    val pulseScale = if (isRunning) rememberPulseAnimation(0.97f, 1.03f, 800) else 1f
    
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    
    val buttonAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, easing = EasingFunctions.EaseOutExpo)
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(buttonSize + 24.dp)
            .graphicsLayer {
                this.alpha = buttonAlpha
                this.scaleX = buttonScale * pressScale * pulseScale
                this.scaleY = buttonScale * pressScale * pulseScale
            }
    ) {
        if (isRunning || isConnecting) {
            Box(
                modifier = Modifier
                    .size(buttonSize + 24.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    buttonColor.copy(alpha = 0.3f),
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
                    strings.statusConnecting,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer { rotationZ = angle }
                )
            } else {
                Icon(
                    if (isRunning) Icons.Filled.LinkOff else Icons.Filled.Link,
                    contentDescription = if (isRunning) strings.stop else strings.start,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusControlPanel(
    state: AppUiState,
    viewModel: MainViewModel,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    strings: AppStrings
) {
    var contentVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        delay(300)
        contentVisible = true
    }

    Column(
        modifier = Modifier.padding(12.dp).fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(300)) + slideInVertically(
                initialOffsetY = { -20 },
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut(tween(200))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                AnimatedIconButton(
                    onClick = onMinimize,
                    content = {
                        Icon(
                            Icons.Filled.Minimize,
                            strings.minimize,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.size(4.dp))
                AnimatedIconButton(
                    onClick = onClose,
                    content = {
                        Icon(
                            Icons.Filled.Close,
                            strings.close,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(400, 100)) + scaleIn(
                initialScale = 0.8f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut(tween(200))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val statusText = when (state.streamState) {
                    StreamState.Idle -> strings.statusIdle
                    StreamState.Connecting -> strings.statusConnecting
                    StreamState.Streaming -> strings.statusStreaming
                    StreamState.Error -> strings.statusError
                }
                
                val statusColor by animateColorAsState(
                    targetValue = when (state.streamState) {
                        StreamState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
                        StreamState.Connecting -> MaterialTheme.colorScheme.tertiary
                        StreamState.Streaming -> MaterialTheme.colorScheme.primary
                        StreamState.Error -> MaterialTheme.colorScheme.error
                    },
                    animationSpec = tween(300)
                )
                
                val statusScale = rememberPulseAnimation(0.98f, 1.02f, 1500)
                
                Text(
                    statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                    modifier = Modifier.scale(statusScale)
                )
                
                AnimatedVisibility(
                    visible = state.errorMessage != null,
                    enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f),
                    exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f)
                ) {
                    if (state.errorMessage != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        if (state.errorMessage!!.contains("adb reverse")) {
                            val parts = state.errorMessage!!.split("\n")
                            val errorTitle = parts.firstOrNull() ?: state.errorMessage!!
                            val cmd = parts.drop(1).joinToString("\n").substringAfter("：").trim()
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    errorTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                                if (cmd.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(0.9f),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                cmd,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(8.dp),
                                                maxLines = 3
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                state.errorMessage!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(400, 200)) + slideInVertically(
                initialOffsetY = { 20 },
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            ),
            exit = fadeOut(tween(200))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val muteInteractionSource = remember { MutableInteractionSource() }
                val isMutePressed by muteInteractionSource.collectIsPressedAsState()
                val muteScale by animateFloatAsState(
                    targetValue = if (isMutePressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                )
                
                val muteColor by animateColorAsState(
                    targetValue = if (state.isMuted)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(300, easing = EasingFunctions.EaseInOutCubic)
                )


                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(
                        onClick = { viewModel.toggleMute() },
                        interactionSource = muteInteractionSource,
                        modifier = Modifier
                            .size(40.dp)
                            .scale(muteScale)
                    ) {
                        Icon(
                            if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (state.isMuted) strings.unmuteLabel else strings.muteLabel,
                            modifier = Modifier.size(20.dp),
                            tint = muteColor
                        )
                    }

                    AnimatedIconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(40.dp),
                        content = {
                            Icon(
                                Icons.Filled.Settings,
                                strings.settingsTitle,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(32.dp),
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    
    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.scale(scale)
    ) {
        content()
    }
}

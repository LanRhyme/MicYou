package com.lanrhyme.micyou

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanrhyme.micyou.animation.EasingFunctions
import kotlinx.coroutines.delay
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopHomeEnhanced(
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
    
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(350, easing = EasingFunctions.EaseOutExpo)
    )
    
    LaunchedEffect(Unit) { visible = true }

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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(state.installMessage ?: "", style = MaterialTheme.typography.bodyMedium)
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
            confirmButton = { Button(onClick = { viewModel.confirmAddFirewallRule() }) { Text(strings.firewallConfirm) } },
            dismissButton = { TextButton(onClick = { viewModel.dismissFirewallDialog() }) { Text(strings.firewallDismiss) } }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderSection(
                platform = platform,
                state = state,
                onMinimize = onMinimize,
                onClose = onClose,
                strings = strings
            )
            
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LeftPanel(
                    state = state,
                    viewModel = viewModel,
                    isBluetoothDisabled = isBluetoothDisabled,
                    strings = strings,
                    modifier = Modifier.weight(0.38f)
                )
                
                CenterPanel(
                    state = state,
                    viewModel = viewModel,
                    audioLevel = audioLevel,
                    strings = strings,
                    modifier = Modifier.weight(0.62f)
                )
            }
            
            BottomBar(
                state = state,
                viewModel = viewModel,
                onOpenSettings = onOpenSettings,
                strings = strings
            )
        }
    }
}

@Composable
private fun HeaderSection(
    platform: Platform,
    state: AppUiState,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    strings: AppStrings
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Router, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Column {
                    Text("MicYou Desktop", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    SelectionContainer {
                        Text(platform.ipAddress, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Minimize, null, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun LeftPanel(
    state: AppUiState,
    viewModel: MainViewModel,
    isBluetoothDisabled: Boolean,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeCard(
            selectedMode = state.mode,
            onModeSelected = { viewModel.setMode(it) },
            isBluetoothDisabled = isBluetoothDisabled,
            strings = strings
        )
        
        if (state.mode != ConnectionMode.Bluetooth) {
            PortCard(
                port = state.port,
                onPortChange = { viewModel.setPort(it) },
                strings = strings
            )
        }
        
        StatusCard(
            streamState = state.streamState,
            errorMessage = state.errorMessage,
            strings = strings,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeCard(
    selectedMode: ConnectionMode,
    onModeSelected: (ConnectionMode) -> Unit,
    isBluetoothDisabled: Boolean,
    strings: AppStrings
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.connectionModeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            val modes = listOfNotNull(
                ConnectionMode.Wifi to (strings.modeWifi to Icons.Rounded.Wifi),
                if (!isBluetoothDisabled) ConnectionMode.Bluetooth to (strings.modeBluetooth to Icons.Rounded.Bluetooth) else null,
                ConnectionMode.Usb to (strings.modeUsb to Icons.Rounded.Usb)
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { (mode, info) ->
                    val (label, icon) = info
                    val isSelected = selectedMode == mode
                    
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        animationSpec = tween(200)
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200)
                    )
                    
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = bgColor,
                        modifier = Modifier.weight(1f).height(42.dp).clickable { onModeSelected(mode) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(icon, null, tint = contentColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortCard(
    port: String,
    onPortChange: (String) -> Unit,
    strings: AppStrings
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.portLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatusCard(
    streamState: StreamState,
    errorMessage: String?,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when (streamState) {
            StreamState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
            StreamState.Connecting -> MaterialTheme.colorScheme.tertiary
            StreamState.Streaming -> MaterialTheme.colorScheme.primary
            StreamState.Error -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(300)
    )
    
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColor.copy(alpha = 0.12f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when (streamState) {
                            StreamState.Idle -> Icons.Rounded.Info
                            StreamState.Connecting -> Icons.Rounded.HourglassTop
                            StreamState.Streaming -> Icons.Rounded.CheckCircle
                            StreamState.Error -> Icons.Rounded.Error
                        },
                        null, tint = statusColor, modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (streamState) {
                    StreamState.Idle -> strings.statusIdle
                    StreamState.Connecting -> strings.statusConnecting
                    StreamState.Streaming -> strings.statusStreaming
                    StreamState.Error -> strings.statusError
                },
                style = MaterialTheme.typography.labelLarge,
                color = statusColor,
                fontWeight = FontWeight.SemiBold
            )
            
            AnimatedVisibility(visible = errorMessage != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                if (errorMessage != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            errorMessage,
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
    }
}

@Composable
private fun CenterPanel(
    state: AppUiState,
    viewModel: MainViewModel,
    audioLevel: Float,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    val isRunning = state.streamState == StreamState.Streaming
    val isConnecting = state.streamState == StreamState.Connecting

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        modifier = modifier.fillMaxHeight()
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isRunning) {
                AudioVisualizer(
                    modifier = Modifier.fillMaxSize(0.88f),
                    audioLevel = audioLevel,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            if (isConnecting) {
                ConnectingAnimation(
                    modifier = Modifier.fillMaxSize(0.88f),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            MainControlButton(
                isRunning = isRunning,
                isConnecting = isConnecting,
                onClick = { if (isRunning || isConnecting) viewModel.stopStream() else viewModel.startStream() },
                strings = strings
            )
        }
    }
}

@Composable
private fun AudioVisualizer(
    modifier: Modifier = Modifier,
    audioLevel: Float,
    color: Color
) {
    val safeAudioLevel = audioLevel.coerceIn(0f, 1f)
    
    val infiniteTransition = rememberInfiniteTransition(label = "AudioViz")
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.98f, targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EasingFunctions.EaseInOutExpo), RepeatMode.Reverse),
        label = "Breath"
    )
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "Wave"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EasingFunctions.EaseInOutCubic), RepeatMode.Reverse),
        label = "Glow"
    )
    
    Canvas(modifier = modifier.graphicsLayer { scaleX = breathScale; scaleY = breathScale }) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        for (i in 0..3) {
            val waveRadius = baseRadius * (0.55f + i * 0.12f * safeAudioLevel)
            val alpha = (0.35f - i * 0.08f) * safeAudioLevel
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = waveRadius, center = center,
                style = Stroke(width = (2.5f - i * 0.4f).dp.toPx())
            )
        }
        
        val barCount = 32
        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f + wavePhase
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            val dynamicLevel = safeAudioLevel * (0.5f + 0.5f * sin(angle * 0.05f + wavePhase * 0.02f))
            val barHeight = baseRadius * 0.12f * dynamicLevel
            val innerRadius = baseRadius * 0.5f
            
            drawLine(
                color = color.copy(alpha = 0.5f * safeAudioLevel),
                start = Offset(center.x + innerRadius * cos(radians), center.y + innerRadius * sin(radians)),
                end = Offset(center.x + (innerRadius + barHeight) * cos(radians), center.y + (innerRadius + barHeight) * sin(radians)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
        
        repeat(6) { i ->
            val progress = i.toFloat() / 6
            val glowRadius = baseRadius * 0.25f * (1f + progress * 0.5f)
            val alpha = glowAlpha * (1f - progress) * safeAudioLevel
            drawCircle(color.copy(alpha = alpha.coerceIn(0f, 0.25f)), glowRadius, center)
        }
    }
}

@Composable
private fun ConnectingAnimation(
    modifier: Modifier = Modifier,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ConnAnim")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "Rot"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1000, easing = EasingFunctions.EaseInOutCubic), RepeatMode.Reverse),
        label = "Pulse"
    )
    
    Canvas(modifier = modifier.graphicsLayer { scaleX = pulse; scaleY = pulse }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2
        
        for (i in 0..2) {
            val arcAngle = rotation + i * 120f
            val sweepAngle = 60f + 20f * sin(rotation * 0.02f)
            drawArc(
                color.copy(alpha = 0.35f - i * 0.1f),
                startAngle = arcAngle, sweepAngle = sweepAngle, useCenter = false,
                topLeft = Offset(center.x - radius * (0.45f + i * 0.12f), center.y - radius * (0.45f + i * 0.12f)),
                size = Size(radius * 2 * (0.45f + i * 0.12f), radius * 2 * (0.45f + i * 0.12f)),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun MainControlButton(
    isRunning: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit,
    strings: AppStrings
) {
    val buttonSize by animateDpAsState(
        targetValue = if (isRunning) 72.dp else 64.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    val buttonColor by animateColorAsState(
        targetValue = when {
            isRunning -> MaterialTheme.colorScheme.error
            isConnecting -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(350)
    )
    
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
    )
    
    val infiniteTransition = rememberInfiniteTransition(label = "BtnGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EasingFunctions.EaseInOutCubic), RepeatMode.Reverse),
        label = "Glow"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(buttonSize + 16.dp).graphicsLayer { scaleX = pressScale; scaleY = pressScale }) {
            if (isRunning) {
                Canvas(modifier = Modifier.size(buttonSize + 14.dp)) {
                    drawCircle(buttonColor.copy(alpha = glowAlpha * 0.35f), size.width / 2)
                }
            }
            
            FloatingActionButton(
                onClick = onClick,
                interactionSource = interactionSource,
                containerColor = buttonColor,
                modifier = Modifier.size(buttonSize),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = if (isPressed) 2.dp else 6.dp)
            ) {
                Icon(
                    if (isConnecting) Icons.Rounded.Refresh else if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    null, modifier = Modifier.size(28.dp), tint = Color.White
                )
            }
        }
        
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when { isRunning -> strings.statusStreaming; isConnecting -> strings.statusConnecting; else -> strings.clickToStart },
                style = MaterialTheme.typography.labelMedium, color = buttonColor, fontWeight = FontWeight.Medium
            )
            if (isRunning) {
                Surface(shape = RoundedCornerShape(3.dp), color = buttonColor) {
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: AppUiState,
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    strings: AppStrings
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MuteButton(
                isMuted = state.isMuted,
                onToggle = { viewModel.toggleMute() },
                strings = strings
            )
            
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Settings, strings.settingsTitle, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MuteButton(
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
        targetValue = if (isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(200)
    )
    val contentColor by animateColorAsState(
        targetValue = if (isMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200)
    )
    
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier.scale(scale).clickable(interactionSource, null) { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                null, tint = contentColor, modifier = Modifier.size(16.dp)
            )
            Text(
                if (isMuted) strings.muteLabel else strings.unmuteLabel,
                style = MaterialTheme.typography.labelSmall, color = contentColor
            )
        }
    }
}

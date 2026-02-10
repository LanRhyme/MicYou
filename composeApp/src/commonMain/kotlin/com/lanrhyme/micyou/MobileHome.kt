package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHome(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val platform = remember { getPlatform() }
    val isClient = platform.type == PlatformType.Android
    
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
             Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                 DesktopSettings(viewModel = viewModel, onClose = { showSettings = false })
             }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MicYou", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "IP: ${platform.ipAddress}", 
                            style = MaterialTheme.typography.bodySmall, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
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
             // 1. Connection Config Card
             Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Mode Selection
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("连接模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                             val modes = listOf(
                                 ConnectionMode.Wifi to "Wi-Fi",
                                 ConnectionMode.Bluetooth to "蓝牙",
                                 ConnectionMode.Usb to "USB"
                             )
                             
                             modes.forEach { (mode, label) ->
                                 FilterChip(
                                     selected = state.mode == mode,
                                     onClick = { viewModel.setMode(mode) },
                                     label = { Text(label) },
                                     leadingIcon = { 
                                         if (state.mode == mode) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) 
                                         else null
                                     },
                                     modifier = Modifier.weight(1f),
                                     shape = CircleShape
                                 )
                             }
                        }
                    }

                    // Inputs
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isClient) {
                             OutlinedTextField(
                                value = state.ipAddress,
                                onValueChange = { viewModel.setIp(it) },
                                label = {
                                    Text(
                                        when (state.mode) {
                                            ConnectionMode.Usb -> "目标 IP (127.0.0.1)"
                                            ConnectionMode.Bluetooth -> "蓝牙设备地址 (MAC)"
                                            else -> "目标 IP"
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
                                label = { Text("端口") },
                                modifier = if (isClient) Modifier.width(100.dp) else Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
             }

             // 2. Mute Card (New Position)
             Card(
                onClick = { viewModel.toggleMute() },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(24.dp)
             ) {
                 Row(
                     modifier = Modifier.padding(20.dp).fillMaxWidth(),
                     horizontalArrangement = Arrangement.Center,
                     verticalAlignment = Alignment.CenterVertically
                 ) {
                     Icon(
                         if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                         contentDescription = null,
                         modifier = Modifier.size(28.dp),
                         tint = if (state.isMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                     )
                     Spacer(modifier = Modifier.width(16.dp))
                     Text(
                         text = if (state.isMuted) "取消静音" else "静音",
                         style = MaterialTheme.typography.titleMedium,
                         color = if (state.isMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                     )
                 }
             }
             
             // 3. Main Control Area (Expands to fill space)
             Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(32.dp)
            ) {
                 Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val isRunning = state.streamState == StreamState.Streaming
                    val isConnecting = state.streamState == StreamState.Connecting
                    
                    // Status Text at top of card
                    Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
                        val (statusColor, statusText) = when(state.streamState) {
                            StreamState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to "点击开始"
                            StreamState.Connecting -> MaterialTheme.colorScheme.primary to "连接中..."
                            StreamState.Streaming -> MaterialTheme.colorScheme.primary to "正在传输音频"
                            StreamState.Error -> MaterialTheme.colorScheme.error to (state.errorMessage ?: "连接错误")
                        }
                        
                        Surface(
                            color = statusColor.copy(alpha = 0.1f),
                            contentColor = statusColor,
                            shape = CircleShape
                        ) {
                            Text(
                                text = statusText,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // Visualizer Background
                    if (isRunning) {
                        // Outer Ripple
                         Box(
                            modifier = Modifier
                                .size(240.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f * (audioLevel * 2).coerceAtMost(1f)),
                                    shape = CircleShape
                                )
                        )
                        
                        CircularProgressIndicator(
                            progress = { audioLevel },
                            modifier = Modifier.size(200.dp),
                            strokeWidth = 12.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                    
                    // Main Button with Bounce Animation
                    val buttonSize by animateDpAsState(if (isRunning) 110.dp else 90.dp)
                    val buttonColor by animateColorAsState(
                        when {
                            isRunning -> MaterialTheme.colorScheme.error
                            isConnecting -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    
                    // Bounce Animation
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) 0.9f else 1f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                    )
                    
                    // Rotation Animation for Connecting
                    val infiniteTransition = rememberInfiniteTransition()
                    val angle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing)
                        ),
                        label = "ConnectionSpinner"
                    )
                    
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
                        modifier = Modifier.size(buttonSize).scale(scale),
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(8.dp)
                    ) {
                        if (isConnecting) {
                            Icon(
                                Icons.Filled.Refresh, 
                                "连接中", 
                                modifier = Modifier.size(40.dp).rotate(angle)
                            )
                        } else {
                            Icon(
                                if (isRunning) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (isRunning) "停止" else "开始",
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
             }
        }
    }
}

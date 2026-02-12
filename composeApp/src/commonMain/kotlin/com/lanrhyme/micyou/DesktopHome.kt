package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopHome(
    viewModel: MainViewModel,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val platform = remember { getPlatform() }
    
    val strings = LocalAppStrings.current
    
    // Startup Animation
    var visible by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = tween(500)
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500)
    )
    
    LaunchedEffect(Unit) {
        visible = true
    }

    if (state.installMessage != null) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismissal */ },
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

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 左侧：网络配置 (Weight 1f)
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MicYou Desktop", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Box {
                            var showIpList by remember { mutableStateOf(false) }
                            val currentIps = remember(showIpList) { 
                                if (showIpList) platform.ipAddresses else emptyList() 
                            }
                            
                            Text(
                                "${strings.ipLabel}${platform.ipAddress}", 
                                style = MaterialTheme.typography.bodySmall, 
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { showIpList = true }
                            )
                            
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

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Connection Mode
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                readOnly = true,
                                value = when (state.mode) {
                                    ConnectionMode.Wifi -> strings.modeWifi
                                    ConnectionMode.Bluetooth -> strings.modeBluetooth
                                    ConnectionMode.Usb -> strings.modeUsb
                                },
                                onValueChange = {},
                                label = { Text(strings.connectionModeLabel) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = expanded
                                    )
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
                                DropdownMenuItem(
                                    text = { Text(strings.modeBluetooth) },
                                    onClick = {
                                        viewModel.setMode(ConnectionMode.Bluetooth)
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.modeUsb) },
                                    onClick = {
                                        viewModel.setMode(ConnectionMode.Usb)
                                        expanded = false
                                    }
                                )
                            }
                        }

                        if (state.mode != ConnectionMode.Bluetooth) {
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

            // 中间：控制与可视化 (Weight 0.8f) - 绝对居中
            Card(
                modifier = Modifier.weight(0.8f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(22.dp)
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isRunning = state.streamState == StreamState.Streaming
                    val isConnecting = state.streamState == StreamState.Connecting
                    val visualSize = (if (maxWidth < maxHeight) maxWidth else maxHeight) * 0.8f

                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                progress = { audioLevel },
                                modifier = Modifier.size(visualSize),
                                strokeWidth = 8.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                                trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f),
                            )
                        }

                        val buttonSize by animateDpAsState(if (isRunning) 72.dp else 64.dp)
                        val buttonColor by animateColorAsState(
                            when {
                                isRunning -> MaterialTheme.colorScheme.error
                                isConnecting -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )

                        val infiniteTransition = rememberInfiniteTransition()
                        val angle by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing)
                            ),
                            label = "ConnectionSpinner"
                        )

                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (isPressed) 0.9f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
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
                            modifier = Modifier.size(buttonSize).scale(scale),
                            shape = RoundedCornerShape(100.dp),
                        ) {
                            if (isConnecting) {
                                Icon(Icons.Filled.Refresh, strings.statusConnecting, modifier = Modifier.rotate(angle))
                            } else {
                                Icon(
                                    if (isRunning) Icons.Filled.MicOff else Icons.Filled.Mic,
                                    contentDescription = if (isRunning) strings.stop else strings.start,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 右侧：状态与控制 (Weight 1f) - 对称布局
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top: Window Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onMinimize, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Minimize, strings.minimize, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, strings.close, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Center: Status
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val statusText = when(state.streamState) {
                            StreamState.Idle -> strings.statusIdle
                            StreamState.Connecting -> strings.statusConnecting
                            StreamState.Streaming -> strings.statusStreaming
                            StreamState.Error -> strings.statusError
                        }
                        
                        Text(statusText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        
                        if (state.errorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                state.errorMessage ?: "", 
                                style = MaterialTheme.typography.labelSmall, 
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2
                            )
                        }
                    }

                    // Bottom: App Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                         FilledTonalIconButton(
                            onClick = { viewModel.toggleMute() },
                            modifier = Modifier.size(40.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (state.isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (state.isMuted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (state.isMuted) strings.unmuteLabel else strings.muteLabel,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Settings, strings.settingsTitle, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

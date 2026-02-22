package com.lanrhyme.micyou

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

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

    // Auto-switch from Bluetooth to WiFi if Bluetooth is disabled on this platform
    LaunchedEffect(isBluetoothDisabled, state.mode) {
        if (isBluetoothDisabled && state.mode == ConnectionMode.Bluetooth) {
            viewModel.setMode(ConnectionMode.Wifi)
        }
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

    if (state.showCloseConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowCloseConfirmDialog(false) },
            title = { Text(strings.closeConfirmTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.closeConfirmMessage)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { 
                            viewModel.setRememberCloseAction(!state.rememberCloseAction) 
                        }
                    ) {
                        Checkbox(
                            checked = state.rememberCloseAction,
                            onCheckedChange = { viewModel.setRememberCloseAction(it) },
                            interactionSource = remember { MutableInteractionSource() }
                        )
                        Text(strings.closeConfirmRemember, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.confirmCloseAction(CloseAction.Minimize, state.rememberCloseAction, onExit = onExitApp, onHide = onHideApp)
                }) {
                    Text(strings.closeConfirmMinimize)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.confirmCloseAction(CloseAction.Exit, state.rememberCloseAction, onExit = onExitApp, onHide = onHideApp)
                }) {
                    Text(strings.closeConfirmExit)
                }
            }
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

                            CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(rippleAlpha = RippleAlpha(
                                0f,
                                0f,
                                0f,
                                0f
                            )
                            )) {
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                    if (isRunning) Icons.Filled.LinkOff else Icons.Filled.Link,
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
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    if (cmd.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    strings.usbAdbReverseHint,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                                SelectionContainer {
                                                    Text(
                                                        cmd,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    state.errorMessage ?: "", 
                                    style = MaterialTheme.typography.labelSmall, 
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
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
                                if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
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

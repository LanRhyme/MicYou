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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.animation.EasingFunctions
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.icon_bluetooth
import micyou.composeapp.generated.resources.icon_home_wifi
import micyou.composeapp.generated.resources.icon_pip
import micyou.composeapp.generated.resources.icon_planet
import micyou.composeapp.generated.resources.icon_settings
import micyou.composeapp.generated.resources.icon_usb
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
    val isDarkTheme = isDarkThemeActive(state.themeMode)
    val forcePureBlackBackground = state.oledPureBlack && isDarkTheme
    
    var visible by remember { mutableStateOf(false) }
    var cardVisible by remember { mutableStateOf(false) }
    
    val hazeState = if (state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground) {
        rememberHazeState()
    } else null
    
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
            text = { 
                Column(
                    modifier = Modifier
                        .widthIn(min = 400.dp, max = 500.dp)
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = strings.firewallMessage.replace("%d", state.pendingFirewallPort?.toString() ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
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
        Box(modifier = Modifier.fillMaxSize()) {
            CustomBackground(
                settings = state.backgroundSettings,
                modifier = Modifier.fillMaxSize(),
                hazeState = hazeState,
                forcePureBlackBackground = forcePureBlackBackground
            )
            
            Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderSection(
                    platform = platform,
                    state = state,
                    onMinimize = onMinimize,
                    onClose = onClose,
                    strings = strings,
                    cardOpacity = state.backgroundSettings.cardOpacity,
                    hazeState = hazeState,
                    visible = cardVisible,
                    delayMillis = 100
                )
                
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LeftPanel(
                        state = state,
                        viewModel = viewModel,
                        isBluetoothDisabled = isBluetoothDisabled,
                        strings = strings,
                        modifier = Modifier.weight(0.38f),
                        cardOpacity = state.backgroundSettings.cardOpacity,
                        hazeState = hazeState,
                        visible = cardVisible,
                        delayMillis = 200
                    )
                    
                    CenterPanel(
                        state = state,
                        viewModel = viewModel,
                        audioLevel = audioLevel,
                        strings = strings,
                        modifier = Modifier.weight(0.62f),
                        cardOpacity = state.backgroundSettings.cardOpacity,
                        hazeState = hazeState,
                        visible = cardVisible,
                        delayMillis = 300
                    )
                }
                
                BottomBar(
                    state = state,
                    viewModel = viewModel,
                    onOpenSettings = onOpenSettings,
                    strings = strings,
                    cardOpacity = state.backgroundSettings.cardOpacity,
                    hazeState = hazeState,
                    visible = cardVisible,
                    delayMillis = 400
                )
            }
        }
    }
}

@Composable
private fun HeaderSection(
    platform: Platform,
    state: AppUiState,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    visible: Boolean = false,
    delayMillis: Int = 0
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

    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = cardAlpha
                this.scaleX = cardScale
                this.scaleY = cardScale
                translationY = cardOffsetY
            },
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
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
                        Icon(painter = painterResource(Res.drawable.icon_pip), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Column {
                    val color1 = MaterialTheme.colorScheme.primary
                    val color2 = MaterialTheme.colorScheme.tertiary
                    
                    val infiniteTransition = rememberInfiniteTransition(label = "DesktopTitleColor")
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
                        "MicYou Desktop", 
                        style = MaterialTheme.typography.titleSmall, 
                        fontWeight = FontWeight.ExtraBold,
                        color = animatedColor
                    )
                    Text("Server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            val ipList = platform.ipAddresses
            val lazyListState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(
                    modifier = Modifier.widthIn(max = 200.dp)
                ) {
                    LazyRow(
                        state = lazyListState,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Scroll) {
                                            val scrollDelta = event.changes.first().scrollDelta.y
                                            coroutineScope.launch {
                                                lazyListState.scrollBy(scrollDelta * 2f)
                                            }
                                        }
                                    }
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(ipList.size) { index ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (index > 0) {
                                    Text(
                                        "•",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                Icon(
                                    painterResource(Res.drawable.icon_planet),
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                SelectionContainer {
                                    Text(
                                        ipList[index],
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    
                    val showLeftFade by remember {
                        derivedStateOf { lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0 }
                    }
                    val showRightFade by remember {
                        derivedStateOf {
                            val layoutInfo = lazyListState.layoutInfo
                            if (layoutInfo.totalItemsCount == 0) false
                            else {
                                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                                lastVisibleItem != null && (lastVisibleItem.index < layoutInfo.totalItemsCount - 1 || 
                                    lastVisibleItem.offset + lastVisibleItem.size > layoutInfo.viewportEndOffset)
                            }
                        }
                    }
                    
                    if (showLeftFade) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(20.dp)
                                .height(24.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHighest,
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }
                    
                    if (showRightFade) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(20.dp)
                                .height(24.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                    )
                                )
                        )
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
    modifier: Modifier = Modifier,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    visible: Boolean = false,
    delayMillis: Int = 0
) {
    val panelAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )
    val panelScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = 0.001f
        )
    )
    val panelOffsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(500, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )

    Column(
        modifier = modifier.graphicsLayer {
            this.alpha = panelAlpha
            this.scaleX = panelScale
            this.scaleY = panelScale
            translationY = panelOffsetY
        },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ModeCard(
            selectedMode = state.mode,
            onModeSelected = { viewModel.setMode(it) },
            isBluetoothDisabled = isBluetoothDisabled,
            strings = strings,
            cardOpacity = cardOpacity,
            hazeState = hazeState,
            enableHaze = state.backgroundSettings.enableHazeEffect
        )
        
        if (state.mode != ConnectionMode.Bluetooth) {
            PortCard(
                port = state.port,
                onPortChange = { viewModel.setPort(it) },
                strings = strings,
                cardOpacity = cardOpacity,
                hazeState = hazeState,
                enableHaze = state.backgroundSettings.enableHazeEffect
            )
        }
        
        StatusCard(
            streamState = state.streamState,
            errorMessage = state.errorMessage,
            strings = strings,
            modifier = Modifier.weight(1f),
            cardOpacity = cardOpacity,
            hazeState = hazeState,
            enableHaze = state.backgroundSettings.enableHazeEffect
        )
    }
}

@Composable
private fun ModeCard(
    selectedMode: ConnectionMode,
    onModeSelected: (ConnectionMode) -> Unit,
    isBluetoothDisabled: Boolean,
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = enableHaze
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.connectionModeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            val modes = listOfNotNull(
                ConnectionMode.Wifi to (strings.modeWifi to painterResource(Res.drawable.icon_home_wifi)),
                if (!isBluetoothDisabled) ConnectionMode.Bluetooth to (strings.modeBluetooth to painterResource(Res.drawable.icon_bluetooth)) else null,
                ConnectionMode.Usb to (strings.modeUsb to painterResource(Res.drawable.icon_usb))
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
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = enableHaze
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.portLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ShardTextField(
                value = port,
                onValueChange = onPortChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
    modifier: Modifier = Modifier,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
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
    
    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = modifier.fillMaxWidth(),
        hazeState = hazeState,
        enabled = enableHaze
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

@Suppress("UnusedBoxWithConstraintsScope")
@Composable
private fun CenterPanel(
    state: AppUiState,
    viewModel: MainViewModel,
    audioLevel: Float,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    visible: Boolean = false,
    delayMillis: Int = 0
) {
    val panelAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )
    val panelScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = 0.001f
        )
    )
    val panelOffsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(500, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )

    val isRunning = state.streamState == StreamState.Streaming
    val isConnecting = state.streamState == StreamState.Connecting

    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f * cardOpacity),
        hazeColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f * cardOpacity * 0.7f),
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                this.alpha = panelAlpha
                this.scaleX = panelScale
                this.scaleY = panelScale
                translationY = panelOffsetY
            },
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val buttonSize = if (isRunning) 72.dp else 64.dp
            val visualSize = buttonSize * 3.5f
            val buttonColor = when {
                isRunning -> MaterialTheme.colorScheme.error
                isConnecting -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isRunning) {
                    AudioVisualizer(
                        modifier = Modifier.size(visualSize),
                        audioLevel = audioLevel,
                        color = MaterialTheme.colorScheme.primary,
                        style = state.visualizerStyle
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
                    onClick = { if (isRunning || isConnecting) viewModel.stopStream() else viewModel.startStream() }
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = buttonSize + 24.dp)
            ) {
                Text(
                    when { isRunning -> strings.statusStreaming; isConnecting -> strings.statusConnecting; else -> strings.clickToStart },
                    style = MaterialTheme.typography.labelMedium,
                    color = buttonColor,
                    fontWeight = FontWeight.Medium
                )
                if (isRunning) {
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = buttonColor
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
        }
    }
}

@Composable
private fun AudioVisualizer(
    modifier: Modifier = Modifier,
    audioLevel: Float,
    color: Color,
    style: VisualizerStyle = VisualizerStyle.Ripple
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
    
    when (style) {
        VisualizerStyle.VolumeRing -> VolumeRingVisualizer(modifier, safeAudioLevel, color)
        VisualizerStyle.Ripple -> RippleVisualizer(modifier, safeAudioLevel, color, breathScale, wavePhase, glowAlpha)
        VisualizerStyle.Bars -> BarsVisualizer(modifier, safeAudioLevel, color, wavePhase)
        VisualizerStyle.Wave -> WaveVisualizer(modifier, safeAudioLevel, color, wavePhase)
        VisualizerStyle.Glow -> GlowVisualizer(modifier, safeAudioLevel, color, glowAlpha, breathScale)
        VisualizerStyle.Particles -> ParticlesVisualizer(modifier, safeAudioLevel, color, wavePhase)
    }
}

@Composable
private fun VolumeRingVisualizer(
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
private fun RippleVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    breathScale: Float,
    wavePhase: Float,
    glowAlpha: Float
) {
    Canvas(modifier = modifier.graphicsLayer { scaleX = breathScale; scaleY = breathScale }) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        for (i in 0..3) {
            val waveRadius = baseRadius * (0.55f + i * 0.12f * audioLevel)
            val alpha = (0.35f - i * 0.08f) * audioLevel
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
            val dynamicLevel = audioLevel * (0.5f + 0.5f * sin(angle * 0.05f + wavePhase * 0.02f))
            val barHeight = baseRadius * 0.12f * dynamicLevel
            val innerRadius = baseRadius * 0.5f
            
            drawLine(
                color = color.copy(alpha = 0.5f * audioLevel),
                start = Offset(center.x + innerRadius * cos(radians), center.y + innerRadius * sin(radians)),
                end = Offset(center.x + (innerRadius + barHeight) * cos(radians), center.y + (innerRadius + barHeight) * sin(radians)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
        
        repeat(6) { i ->
            val progress = i.toFloat() / 6
            val glowRadius = baseRadius * 0.25f * (1f + progress * 0.5f)
            val alpha = glowAlpha * (1f - progress) * audioLevel
            drawCircle(color.copy(alpha = alpha.coerceIn(0f, 0.25f)), glowRadius, center)
        }
    }
}

@Composable
private fun BarsVisualizer(
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
private fun WaveVisualizer(
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
private fun GlowVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    glowAlpha: Float,
    breathScale: Float
) {
    Canvas(modifier = modifier.graphicsLayer { scaleX = breathScale; scaleY = breathScale }) {
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
private fun ParticlesVisualizer(
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
    onClick: () -> Unit
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

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(buttonSize + 16.dp).graphicsLayer { scaleX = pressScale; scaleY = pressScale }
    ) {
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
}

@Composable
private fun BottomBar(
    state: AppUiState,
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    strings: AppStrings,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    visible: Boolean = false,
    delayMillis: Int = 0
) {
    val barAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )
    val barScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = 0.001f
        )
    )
    val barOffsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(500, delayMillis, easing = EasingFunctions.EaseOutExpo)
    )

    HazeSurface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = barAlpha
                this.scaleX = barScale
                this.scaleY = barScale
                translationY = barOffsetY
            },
        hazeState = hazeState,
        enabled = state.backgroundSettings.enableHazeEffect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MuteButton(
                    isMuted = state.isMuted,
                    onToggle = { viewModel.toggleMute() },
                    strings = strings
                )
                MonitorButton(
                    isMonitoring = state.monitoringEnabled,
                    onToggle = { viewModel.setMonitoringEnabled(!state.monitoringEnabled) },
                    strings = strings
                )
            }
            
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(Res.drawable.icon_settings),
                    contentDescription = strings.settingsTitle,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                if (isMuted) strings.unmuteLabel else strings.muteLabel,
                style = MaterialTheme.typography.labelSmall, color = contentColor
            )
        }
    }
}

@Composable
private fun MonitorButton(
    isMonitoring: Boolean,
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
        targetValue = if (isMonitoring) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(200)
    )
    val contentColor by animateColorAsState(
        targetValue = if (isMonitoring) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (isMonitoring) Icons.Rounded.Headphones else Icons.Rounded.HeadsetOff,
                null, tint = contentColor, modifier = Modifier.size(16.dp)
            )
            Text(
                strings.monitoringLabel,
                style = MaterialTheme.typography.labelSmall, color = contentColor
            )
        }
    }
}

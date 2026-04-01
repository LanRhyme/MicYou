package com.lanrhyme.micyou

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lanrhyme.micyou.animation.EasingFunctions
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.InstallDesktop
import androidx.compose.material.icons.rounded.Delete

/**
 * 可复用的设置项容器组件
 */
@Composable
private fun SettingsItemContainer(
    modifier: Modifier = Modifier,
    cardOpacity: Float = 1f,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        content()
    }
}

/**
 * 可复用的设置项开关组件
 */
@Composable
private fun SettingsSwitchItem(
    headline: String,
    supporting: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    cardOpacity: Float = 1f
) {
    SettingsItemContainer(
        cardOpacity = cardOpacity,
        onClick = { onCheckedChange(!checked) }
    ) {
        ListItem(
            headlineContent = { Text(headline) },
            supportingContent = supporting?.let { { Text(it) } },
            trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
            modifier = Modifier.clickable { onCheckedChange(!checked) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

/**
 * 可复用的设置项下拉选择组件
 */
@Composable
private fun <T> SettingsDropdownItem(
    headline: String,
    selected: T,
    options: List<T>,
    labelProvider: (T) -> String,
    onSelect: (T) -> Unit,
    cardOpacity: Float = 1f
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsItemContainer(cardOpacity = cardOpacity) {
        ListItem(
            headlineContent = { Text(headline) },
            trailingContent = {
                Box {
                    TextButton(onClick = { expanded = true }) { Text(labelProvider(selected)) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = MaterialTheme.shapes.medium) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(labelProvider(option)) },
                                onClick = { onSelect(option); expanded = false },
                                trailingIcon = { if (selected == option) Icon(Icons.Default.Check, contentDescription = null) }
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

enum class SettingsSection {
    General,
    Appearance,
    Audio,
    Plugins,
    About
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopSettings(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val platform = getPlatform()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    val isDarkTheme = isDarkThemeActive(state.themeMode)
    val forcePureBlackBackground = state.oledPureBlack && isDarkTheme
    
    // Haze state for background effect
    val hazeState = if (state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground) {
        rememberHazeState()
    } else null
    
    // 页面进入动画状态
    var visible by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, easing = EasingFunctions.EaseOutExpo),
        label = "pageAlpha"
    )
    
    LaunchedEffect(Unit) {
        visible = true
        delay(100)
        contentVisible = true
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // 当使用自定义背景时，Surface 使用透明色
    val surfaceColor = if (state.backgroundSettings.hasCustomBackground) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    Surface(
        color = surfaceColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CustomBackground(
                settings = state.backgroundSettings,
                modifier = Modifier.fillMaxSize(),
                hazeState = hazeState,
                forcePureBlackBackground = forcePureBlackBackground
            )
            
            if (platform.type == PlatformType.Desktop) {
                DesktopLayout(viewModel, onClose, contentVisible, hazeState)
            } else {
                MobileLayout(viewModel, onClose, hazeState)
            }
        }
    }
}

@Composable
fun DesktopLayout(viewModel: MainViewModel, onClose: () -> Unit, contentVisible: Boolean, hazeState: HazeState?) {
    var currentSection by remember { mutableStateOf(SettingsSection.General) }
    val strings = LocalAppStrings.current
    val state by viewModel.uiState.collectAsState()
    val cardOpacity = state.backgroundSettings.cardOpacity
    
    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 左侧导航栏 - 使用 AnimatedCard 样式
        AnimatedSettingsCard(
            visible = contentVisible,
            delayMillis = 100,
            modifier = Modifier.width(240.dp).fillMaxHeight(),
            cardOpacity = cardOpacity,
            hazeState = hazeState,
            enableHaze = state.backgroundSettings.enableHazeEffect,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = strings.close,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            strings.settingsTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                item { 
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    ) 
                }
                
                itemsIndexed(SettingsSection.entries.toList()) { index, section ->
                    val icon = when (section) {
                        SettingsSection.General -> Icons.Rounded.Settings
                        SettingsSection.Appearance -> Icons.Rounded.Palette
                        SettingsSection.Audio -> Icons.Rounded.Mic
                        SettingsSection.Plugins -> Icons.Rounded.Extension
                        SettingsSection.About -> Icons.Rounded.Info
                    }
                    val isSelected = currentSection == section
                    
                    // 导航项进入动画
                    var navItemVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(contentVisible) {
                        if (contentVisible) {
                            delay(150L + index * 50L)
                            navItemVisible = true
                        }
                    }
                    
                    AnimatedVisibility(
                        visible = navItemVisible,
                        enter = slideInHorizontally(
                            initialOffsetX = { -30 },
                            animationSpec = tween(300, easing = EasingFunctions.EaseOutExpo)
                        ) + fadeIn(tween(300)),
                        exit = fadeOut(tween(200))
                    ) {
                        NavigationItem(
                            icon = icon,
                            label = section.getLabel(strings),
                            isSelected = isSelected,
                            onClick = { currentSection = section }
                        )
                    }
                }
            }
        }
        
        // 右侧内容区 - 使用 AnimatedCard 样式
        AnimatedSettingsCard(
            visible = contentVisible,
            delayMillis = 200,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            cardOpacity = cardOpacity,
            hazeState = hazeState,
            enableHaze = state.backgroundSettings.enableHazeEffect,
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier.padding(top = 20.dp, start = 24.dp, end = 24.dp, bottom = 24.dp)
            ) {
                // 标题区域
                AnimatedContent(
                    targetState = currentSection,
                    transitionSpec = {
                        (slideInHorizontally(
                            initialOffsetX = { if (targetState.ordinal > initialState.ordinal) 50 else -50 },
                            animationSpec = tween(300, easing = EasingFunctions.EaseOutExpo)
                        ) + fadeIn(tween(250))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { if (targetState.ordinal > initialState.ordinal) -50 else 50 },
                            animationSpec = tween(250, easing = EasingFunctions.EaseInExpo)
                        ) + fadeOut(tween(200)))
                    },
                    label = "sectionTitle"
                ) { section ->
                    Text(
                        section.getLabel(strings),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                // 内容区域 - 使用 AnimatedContent 实现页面切换动画
                AnimatedContent(
                    targetState = currentSection,
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(
                            initialOffsetX = { direction * 100 },
                            animationSpec = tween(350, easing = EasingFunctions.EaseOutExpo)
                        ) + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { -direction * 100 },
                            animationSpec = tween(300, easing = EasingFunctions.EaseInExpo)
                        ) + fadeOut(tween(250)))
                    },
                    label = "sectionContent"
                ) { section ->
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            SettingsContent(section, viewModel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 导航项组件 - 带选中动画效果
 */
@Composable
private fun NavigationItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            isHovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "navBackground"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "navScale"
    )
    
    val iconTint by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "navIconTint"
    )
    
    val textColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "navTextColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .scale(scale)
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(backgroundColor)
            .animateContentSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = iconTint
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = textColor
        )
        
        Spacer(modifier = Modifier.weight(1f))

        // 选中指示器
        androidx.compose.animation.AnimatedVisibility(
            visible = isSelected,
            enter = scaleIn(
                initialScale = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(tween(200)),
            exit = scaleOut(
                targetScale = 0f,
                animationSpec = tween(150)
            ) + fadeOut(tween(100))
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(50)
                    )
            )
        }
    }
}

/**
 * 设置页面卡片组件 - 带进入动画
 */
@Composable
private fun AnimatedSettingsCard(
    visible: Boolean,
    delayMillis: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false,
    content: @Composable () -> Unit
) {
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, delayMillis, easing = EasingFunctions.EaseOutExpo),
        label = "cardAlpha"
    )
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
            visibilityThreshold = 0.001f
        ),
        label = "cardScale"
    )
    val cardOffsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 40f,
        animationSpec = tween(500, delayMillis, easing = EasingFunctions.EaseOutExpo),
        label = "cardOffsetY"
    )

    if (enableHaze && hazeState != null) {
        HazeCard(
            hazeState = hazeState,
            enabled = true,
            hazeColor = containerColor.copy(alpha = cardOpacity * 0.7f),
            modifier = modifier
                .graphicsLayer {
                    this.alpha = cardAlpha
                    this.scaleX = cardScale
                    this.scaleY = cardScale
                    translationY = cardOffsetY
                }
                .clip(shape)
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier
                .graphicsLayer {
                    this.alpha = cardAlpha
                    this.scaleX = cardScale
                    this.scaleY = cardScale
                    translationY = cardOffsetY
                },
            colors = CardDefaults.cardColors(
                containerColor = containerColor.copy(alpha = cardOpacity)
            ),
            shape = shape,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileLayout(viewModel: MainViewModel, onClose: () -> Unit, hazeState: HazeState?) {
    val strings = LocalAppStrings.current
    val state by viewModel.uiState.collectAsState()
    val cardOpacity = state.backgroundSettings.cardOpacity
    val isDarkTheme = isDarkThemeActive(state.themeMode)
    
    // 根据主题模式选择毛玻璃颜色：浅色模式用浅色，深色模式用深色
    val hazeContainerColor = if (isDarkTheme) {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)
    }
    
    // 页面进入动画
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }
    
    // 当使用自定义背景时，Scaffold 使用透明色
    val scaffoldColor = if (state.backgroundSettings.hasCustomBackground) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settingsTitle) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.close)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (state.backgroundSettings.hasCustomBackground) {
                        if (isDarkTheme) {
                            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        }
                    } else {
                        if (isDarkTheme) {
                            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = cardOpacity)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = cardOpacity)
                        }
                    },
                    scrolledContainerColor = if (state.backgroundSettings.hasCustomBackground) {
                        if (isDarkTheme) {
                            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        }
                    } else {
                        if (isDarkTheme) {
                            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = cardOpacity)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = cardOpacity)
                        }
                    }
                )
            )
        },
        containerColor = scaffoldColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(SettingsSection.entries.toList()) { index, section ->
                // 使用 graphicsLayer 实现动画，不会阻塞滚动
                var cardAnimated by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(100L + index * 80L)
                    cardAnimated = true
                }
                
                val cardAlpha by animateFloatAsState(
                    targetValue = if (cardAnimated) 1f else 0f,
                    animationSpec = tween(350, easing = EasingFunctions.EaseOutExpo),
                    label = "cardAlpha"
                )
                val cardOffsetY by animateFloatAsState(
                    targetValue = if (cardAnimated) 0f else 60f,
                    animationSpec = tween(400, easing = EasingFunctions.EaseOutExpo),
                    label = "cardOffsetY"
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.alpha = cardAlpha
                            translationY = cardOffsetY
                        }
                ) {
                    if (state.backgroundSettings.enableHazeEffect && hazeState != null) {
                        HazeSurface(
                            hazeState = hazeState,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = hazeContainerColor,
                            hazeColor = hazeContainerColor
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    section.getLabel(strings),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(12.dp))
                                SettingsContent(section, viewModel)
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = hazeContainerColor
                            ),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    section.getLabel(strings),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(12.dp))
                                SettingsContent(section, viewModel)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(section: SettingsSection, viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val platform = getPlatform()
    val strings = LocalAppStrings.current
    val cardOpacity = state.backgroundSettings.cardOpacity

    // 预设种子颜色 - Material Design 3 多样化配色方案
    val seedColors = listOf(
        0xFF4285F4L, // Google Blue (Default) - 蓝色
        0xFF6750A4L, // Material Purple - 紫色
        0xFFE91E63L, // Pink - 粉色
        0xFFF44336L, // Red - 红色
        0xFFFF9800L, // Orange - 橙色
        0xFF4CAF50L, // Green - 绿色
        0xFF009688L, // Teal - 青绿色
        0xFF9C27B0L  // Deep Purple - 深紫
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (section) {
            SettingsSection.General -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsDropdownItem(
                        headline = strings.languageLabel,
                        selected = state.language,
                        options = AppLanguage.entries.toList(),
                        labelProvider = { it.label },
                        onSelect = { viewModel.setLanguage(it) },
                        cardOpacity = cardOpacity
                    )

                    if (platform.type == PlatformType.Android) {
                        SettingsSwitchItem(
                            headline = strings.enableStreamingNotificationLabel,
                            checked = state.enableStreamingNotification,
                            onCheckedChange = { viewModel.setEnableStreamingNotification(it) },
                            cardOpacity = cardOpacity
                        )

                        SettingsSwitchItem(
                            headline = strings.keepScreenOnLabel,
                            supporting = strings.keepScreenOnDesc,
                            checked = state.keepScreenOn,
                            onCheckedChange = { viewModel.setKeepScreenOn(it) },
                            cardOpacity = cardOpacity
                        )
                    }

                    if (platform.type == PlatformType.Desktop) {
                        SettingsSwitchItem(
                            headline = strings.autoStartLabel,
                            supporting = strings.autoStartDesc,
                            checked = state.autoStart,
                            onCheckedChange = { viewModel.setAutoStart(it) },
                            cardOpacity = cardOpacity
                        )
                        SettingsDropdownItem(
                            headline = strings.closeActionLabel,
                            selected = state.closeAction,
                            options = CloseAction.entries.toList(),
                            labelProvider = { action ->
                                when (action) {
                                    CloseAction.Prompt -> strings.closeActionPrompt
                                    CloseAction.Minimize -> strings.closeActionMinimize
                                    CloseAction.Exit -> strings.closeActionExit
                                }
                            },
                            onSelect = { viewModel.setCloseAction(it) },
                            cardOpacity = cardOpacity
                        )
                        SettingsSwitchItem(
                            headline = strings.pocketModeLabel,
                            supporting = strings.pocketModeDesc,
                            checked = state.pocketMode,
                            onCheckedChange = { viewModel.setPocketMode(it) },
                            cardOpacity = cardOpacity
                        )
                        SettingsSwitchItem(
                            headline = strings.useSystemTitleBarLabel,
                            supporting = strings.useSystemTitleBarDesc,
                            checked = state.useSystemTitleBar,
                            onCheckedChange = { viewModel.setUseSystemTitleBar(it) },
                            cardOpacity = cardOpacity
                        )
                        SettingsSwitchItem(
                            headline = strings.floatingWindowLabel,
                            supporting = strings.floatingWindowDesc,
                            checked = state.floatingWindowEnabled,
                            onCheckedChange = { viewModel.setFloatingWindowEnabled(it) },
                            cardOpacity = cardOpacity
                        )

                        if (platform.isWindows) {
                            VBCableSettingsSection(
                                cardOpacity = cardOpacity,
                                strings = strings
                            )
                        }
                    }

                    // Auto check update toggle (all platforms)
                    SettingsSwitchItem(
                        headline = strings.autoCheckUpdateLabel,
                        supporting = strings.autoCheckUpdateDesc,
                        checked = state.autoCheckUpdate,
                        onCheckedChange = { viewModel.setAutoCheckUpdate(it) },
                        cardOpacity = cardOpacity
                    )

                    // Mirror download toggle (all platforms)
                    SettingsSwitchItem(
                        headline = strings.useMirrorDownloadLabel,
                        supporting = strings.useMirrorDownloadDesc,
                        checked = state.useMirrorDownload,
                        onCheckedChange = { viewModel.setUseMirrorDownload(it) },
                        cardOpacity = cardOpacity
                    )
                }
            }
            SettingsSection.Appearance -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.themeLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(ThemeMode.entries) { mode ->
                                    FilterChip(
                                        selected = state.themeMode == mode,
                                        onClick = { viewModel.setThemeMode(mode) },
                                        label = { 
                                            Text(when(mode) {
                                                ThemeMode.System -> strings.themeSystem
                                                ThemeMode.Light -> strings.themeLight
                                                ThemeMode.Dark -> strings.themeDark
                                            }) 
                                        },
                                        leadingIcon = {
                                            if (state.themeMode == mode) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) else null
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 动态取色选项：Android 和 Windows 支持
                    if (platform.type == PlatformType.Android || isDynamicColorSupported()) {
                        SettingsSwitchItem(
                            headline = strings.useDynamicColorLabel,
                            supporting = strings.useDynamicColorDesc,
                            checked = state.useDynamicColor,
                            onCheckedChange = { viewModel.setUseDynamicColor(it) },
                            cardOpacity = cardOpacity
                        )
                    }

                    SettingsSwitchItem(
                        headline = strings.oledPureBlackLabel,
                        supporting = strings.oledPureBlackDesc,
                        checked = state.oledPureBlack,
                        onCheckedChange = { viewModel.setOledPureBlack(it) },
                        cardOpacity = cardOpacity
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.themeColorLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            val isSeedColorEnabled = !state.useDynamicColor
                            // 当开启动态取色时，显示当前实际应用的主题主色（动态颜色）
                            val displayColor = if (state.useDynamicColor) {
                                MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFF
                            } else {
                                state.seedColor
                            }
                            ColorSelectorWithPicker(
                                selectedColor = displayColor,
                                presetColors = seedColors,
                                onColorSelected = { viewModel.setSeedColor(it) },
                                enabled = isSeedColorEnabled,
                                disabledHint = strings.dynamicColorEnabledHint,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 开启动态取色时，显示遮罩覆盖整个框
                        if (state.useDynamicColor) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                    .clickable(enabled = false) { },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = strings.dynamicColorEnabledHint,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.visualizerStyleLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(VisualizerStyle.entries) { style ->
                                    FilterChip(
                                        selected = state.visualizerStyle == style,
                                        onClick = { viewModel.setVisualizerStyle(style) },
                                        label = { 
                                            Text(when(style) {
                                                VisualizerStyle.VolumeRing -> strings.visualizerStyleVolumeRing
                                                VisualizerStyle.Ripple -> strings.visualizerStyleRipple
                                                VisualizerStyle.Bars -> strings.visualizerStyleBars
                                                VisualizerStyle.Wave -> strings.visualizerStyleWave
                                                VisualizerStyle.Glow -> strings.visualizerStyleGlow
                                                VisualizerStyle.Particles -> strings.visualizerStyleParticles
                                            }) 
                                        },
                                        leadingIcon = {
                                            if (state.visualizerStyle == style) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) else null
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.backgroundSettingsLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.pickBackgroundImage() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(strings.selectBackgroundImage)
                                }
                                if (state.backgroundSettings.hasCustomBackground) {
                                    OutlinedButton(
                                        onClick = { viewModel.clearBackgroundImage() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(strings.clearBackgroundImage)
                                    }
                                }
                            }
                            
                            if (state.backgroundSettings.hasCustomBackground) {
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        "${strings.backgroundBrightnessLabel}: ${(state.backgroundSettings.brightness * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Slider(
                                        value = state.backgroundSettings.brightness,
                                        onValueChange = { viewModel.setBackgroundBrightness(it) },
                                        valueRange = 0f..1f
                                    )
                                }
                                
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        "${strings.backgroundBlurLabel}: ${state.backgroundSettings.blurRadius.toInt()}px",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Slider(
                                        value = state.backgroundSettings.blurRadius,
                                        onValueChange = { viewModel.setBackgroundBlur(it) },
                                        valueRange = 0f..50f
                                    )
                                }
                                
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        "${strings.cardOpacityLabel}: ${(state.backgroundSettings.cardOpacity * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Slider(
                                        value = state.backgroundSettings.cardOpacity,
                                        onValueChange = { viewModel.setCardOpacity(it) },
                                        valueRange = 0f..1f
                                    )
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(strings.enableHazeEffectLabel, style = MaterialTheme.typography.bodyMedium)
                                        Text(strings.enableHazeEffectDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = state.backgroundSettings.enableHazeEffect,
                                        onCheckedChange = { viewModel.setEnableHazeEffect(it) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SettingsSection.Audio -> {
                if (platform.type == PlatformType.Android) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.autoConfigLabel) },
                                supportingContent = { Text(strings.autoConfigDesc) },
                                trailingContent = {
                                    Switch(
                                        checked = state.isAutoConfig,
                                        onCheckedChange = { viewModel.setAutoConfig(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setAutoConfig(!state.isAutoConfig) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        
                        val manualSettingsEnabled = !state.isAutoConfig
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.sampleRateLabel) },
                                trailingContent = {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { expanded = true },
                                            enabled = manualSettingsEnabled
                                        ) { Text("${state.sampleRate.value} Hz") }
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            SampleRate.entries.forEach { rate ->
                                                DropdownMenuItem(text = { Text("${rate.value} Hz") }, onClick = { viewModel.setSampleRate(rate); expanded = false })
                                            }
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.channelCountLabel) },
                                trailingContent = {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { expanded = true },
                                            enabled = manualSettingsEnabled
                                        ) { Text(state.channelCount.label) }
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            ChannelCount.entries.forEach { count ->
                                                DropdownMenuItem(text = { Text(count.label) }, onClick = { viewModel.setChannelCount(count); expanded = false })
                                            }
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.audioFormatLabel) },
                                trailingContent = {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { expanded = true },
                                            enabled = manualSettingsEnabled
                                        ) { Text(state.audioFormat.label) }
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            AudioFormat.entries.forEach { format ->
                                                DropdownMenuItem(text = { Text(format.label) }, onClick = { viewModel.setAudioFormat(format); expanded = false })
                                            }
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.audioSourceLabel) },
                                trailingContent = {
                                    var expanded by remember { mutableStateOf(false) }
                                    val audioSourceOptions = getAudioSourceOptions()
                                    val currentSource = audioSourceOptions.find { it.name == state.androidAudioSourceName } ?: audioSourceOptions.firstOrNull()
                                    if (audioSourceOptions.isNotEmpty() && currentSource != null) {
                                        Box {
                                            TextButton(onClick = { expanded = true }) { Text(currentSource.label) }
                                            DropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false },
                                                shape = MaterialTheme.shapes.medium
                                            ) {
                                                audioSourceOptions.forEach { source ->
                                                    DropdownMenuItem(
                                                        text = { Text(source.label) },
                                                        onClick = { viewModel.setAndroidAudioSource(source.name); expanded = false },
                                                        trailingIcon = { if (currentSource == source) Icon(Icons.Default.Check, contentDescription = null) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                } else {
                    // Desktop audio settings
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 1. 增益 (Amplifier) - 第一行显示
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(strings.gainLabel, style = MaterialTheme.typography.bodyMedium)

                                Slider(
                                    value = state.amplification,
                                    onValueChange = { viewModel.setAmplification(it) },
                                    valueRange = -50.0f..50.0f,
                                    modifier = Modifier.weight(1f)
                                )

                                val gainText = if (state.amplification >= 0) "+${state.amplification.toInt()} dB" else "${state.amplification.toInt()} dB"
                                Text(
                                    gainText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(60.dp),
                                    textAlign = TextAlign.End
                                )
                            }
                        }

                        // 2. 降噪 (Noise Suppression)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableNsLabel) },
                                trailingContent = { 
                                    Switch(
                                        checked = state.enableNS, 
                                        onCheckedChange = { viewModel.setEnableNS(it) }
                                    ) 
                                },
                                modifier = Modifier.clickable { viewModel.setEnableNS(!state.enableNS) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableNS) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(strings.nsTypeLabel, style = MaterialTheme.typography.bodyMedium)
                                        var showHelp by remember { mutableStateOf(false) }
                                        IconButton(onClick = { showHelp = true }) {
                                            Icon(Icons.Default.Info, contentDescription = "Help", modifier = Modifier.size(20.dp))
                                        }
                                        if (showHelp) {
                                            NoiseReductionHelpPopup(onDismiss = { showHelp = false })
                                        }
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(NoiseReductionType.entries) { type ->
                                            FilterChip(
                                                selected = state.nsType == type,
                                                onClick = { viewModel.setNsType(type) },
                                                label = { Text(type.label) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. 去混响 (Dereverb)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableDereverbLabel) },
                                trailingContent = { 
                                    Switch(
                                        checked = state.enableDereverb, 
                                        onCheckedChange = { viewModel.setEnableDereverb(it) }
                                    ) 
                                },
                                modifier = Modifier.clickable { viewModel.setEnableDereverb(!state.enableDereverb) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableDereverb) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("${strings.dereverbLevelLabel}: ${(state.dereverbLevel * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = state.dereverbLevel,
                                        onValueChange = { viewModel.setDereverbLevel(it) },
                                        valueRange = 0f..1f
                                    )
                                }
                            }
                        }

                        // 4. 自动增益控制 (AGC)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableAgcLabel) },
                                trailingContent = { 
                                    Switch(
                                        checked = state.enableAGC, 
                                        onCheckedChange = { viewModel.setEnableAGC(it) }
                                    ) 
                                },
                                modifier = Modifier.clickable { viewModel.setEnableAGC(!state.enableAGC) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableAGC) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("${strings.agcTargetLabel}: ${state.agcTargetLevel}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = state.agcTargetLevel.toFloat(),
                                        onValueChange = { viewModel.setAgcTargetLevel(it.toInt()) },
                                        valueRange = 0f..100f
                                    )
                                }
                            }
                        }

                        // 5. 语音活动检测 (VAD)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableVadLabel) },
                                trailingContent = { 
                                    Switch(
                                        checked = state.enableVAD, 
                                        onCheckedChange = { viewModel.setEnableVAD(it) }
                                    ) 
                                },
                                modifier = Modifier.clickable { viewModel.setEnableVAD(!state.enableVAD) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableVAD) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("${strings.vadThresholdLabel}: ${state.vadThreshold}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = state.vadThreshold.toFloat(),
                                        onValueChange = { viewModel.setVadThreshold(it.toInt()) },
                                        valueRange = 0f..100f
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SettingsSection.Plugins -> {
                PluginSettingsContent(viewModel, cardOpacity)
            }
            SettingsSection.About -> {
                val uriHandler = LocalUriHandler.current
                var showLicenseDialog by remember { mutableStateOf(false) }
                var showContributorsDialog by remember { mutableStateOf(false) }

                if (showContributorsDialog) {
                    ContributorsDialog(onDismiss = { showContributorsDialog = false })
                }

                if (showLicenseDialog) {
                    AlertDialog(
                        onDismissRequest = { showLicenseDialog = false },
                        title = { Text(strings.licensesTitle) },
                        text = {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    Text(strings.basedOnAndroidMic, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(8.dp))
                                }
                                item {
                                    Text("AndroidMic", style = MaterialTheme.typography.titleSmall)
                                    Text("MIT License", style = MaterialTheme.typography.bodySmall)
                                }
                                item {
                                    Text("JetBrains Compose Multiplatform", style = MaterialTheme.typography.titleSmall)
                                    Text("Apache License 2.0", style = MaterialTheme.typography.bodySmall)
                                }
                                item {
                                    Text("Kotlin Coroutines", style = MaterialTheme.typography.titleSmall)
                                    Text("Apache License 2.0", style = MaterialTheme.typography.bodySmall)
                                }
                                item {
                                    Text("Ktor", style = MaterialTheme.typography.titleSmall)
                                    Text("Apache License 2.0", style = MaterialTheme.typography.bodySmall)
                                }
                                item {
                                    Text("Material Components", style = MaterialTheme.typography.titleSmall)
                                    Text("Apache License 2.0", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showLicenseDialog = false }) {
                                Text(strings.close)
                            }
                        }
                    )
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.developerLabel) },
                            supportingContent = { Text("LanRhyme、ChinsaaWei") },
                            leadingContent = { Icon(Icons.Rounded.Person, null,modifier = Modifier.size(24.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.githubRepoLabel) },
                            supportingContent = { 
                                Text(
                                    "https://github.com/LanRhyme/MicYou",
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable { uriHandler.openUri("https://github.com/LanRhyme/MicYou") }
                                ) 
                            },
                            leadingContent = { Icon(Icons.Rounded.Language, null,modifier = Modifier.size(24.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.contributorsLabel) },
                            supportingContent = { Text(strings.contributorsDesc) },
                            leadingContent = { Icon(Icons.Rounded.People, null,modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.clickable { showContributorsDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.versionLabel) },
                            supportingContent = { Text(getAppVersion()) },
                            leadingContent = { Icon(Icons.Rounded.Info, null,modifier = Modifier.size(24.dp)) },
                            trailingContent = {
                                TextButton(onClick = { viewModel.checkUpdateManual() }) {
                                    Text(strings.checkUpdate)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.openSourceLicense) },
                            supportingContent = { Text(strings.viewLibraries) },
                            leadingContent = { Icon(Icons.Rounded.Description, null,modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.clickable { showLicenseDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.exportLog) },
                            supportingContent = { Text(strings.exportLogDesc) },
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null,modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.clickable {
                                viewModel.exportLog { path ->
                                    if (path != null) {
                                        viewModel.showSnackbar("${strings.logExported}: $path")
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = cardOpacity * 0.7f)
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                     Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.softwareIntro, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            strings.introText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                     }
                }
            }
        }
    }
}

fun SettingsSection.getLabel(strings: AppStrings): String {
    return when (this) {
        SettingsSection.General -> strings.generalSection
        SettingsSection.Appearance -> strings.appearanceSection
        SettingsSection.Audio -> strings.audioSection
        SettingsSection.Plugins -> strings.pluginsSection
        SettingsSection.About -> strings.aboutSection
    }
}

/**
 * 降噪算法帮助 Popup
 */
@Composable
fun NoiseReductionHelpPopup(onDismiss: () -> Unit) {
    val strings = LocalAppStrings.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // 标题
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            strings.nsAlgorithmHelpTitle,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // RNNoise
                    AlgorithmInfoItem(
                        title = strings.nsAlgorithmRNNoiseTitle,
                        description = strings.nsAlgorithmRNNoiseDesc,
                        recommendation = strings.nsAlgorithmRecommended,
                        isRecommended = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Ulunas (ONNX)
                    AlgorithmInfoItem(
                        title = strings.nsAlgorithmUlnasTitle,
                        description = strings.nsAlgorithmUlnasDesc,
                        recommendation = strings.nsAlgorithmAlternative,
                        isRecommended = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Speexdsp
                    AlgorithmInfoItem(
                        title = strings.nsAlgorithmSpeexdspTitle,
                        description = strings.nsAlgorithmSpeexdspDesc,
                        recommendation = strings.nsAlgorithmLightweight,
                        isRecommended = false
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 关闭按钮
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(strings.nsAlgorithmCloseButton)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlgorithmInfoItem(
    title: String,
    description: String,
    recommendation: String,
    isRecommended: Boolean
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            if (recommendation.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (isRecommended) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        recommendation,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRecommended) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun VBCableSettingsSection(
    cardOpacity: Float,
    strings: AppStrings
) {
    var isVBCableInstalled by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    
    val progressFlow = getVBCableInstallProgress()
    val progress by progressFlow.collectAsState(initial = null)
    
    LaunchedEffect(Unit) {
        isVBCableInstalled = isVirtualDeviceInstalled()
    }
    
    LaunchedEffect(progress) {
        if (progress != null) {
            installing = true
            statusMessage = progress
        } else {
            if (installing) {
                isVBCableInstalled = isVirtualDeviceInstalled()
                installing = false
                statusMessage = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    strings.vbcableSettingsLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!installing) {
                    Text(
                        if (isVBCableInstalled) strings.vbcableInstalled else strings.vbcableNotInstalled,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isVBCableInstalled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (statusMessage != null) {
                Text(
                    statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { 
                        installing = true
                        statusMessage = strings.vbcableInstalling
                        kotlinx.coroutines.GlobalScope.launch {
                            installVBCable()
                        }
                    },
                    enabled = !installing && !isVBCableInstalled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Rounded.InstallDesktop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.vbcableInstall)
                }
                
                OutlinedButton(
                    onClick = { 
                        installing = true
                        statusMessage = strings.vbcableUninstalling
                        kotlinx.coroutines.GlobalScope.launch {
                            uninstallVirtualDevice()
                        }
                    },
                    enabled = !installing && isVBCableInstalled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.vbcableUninstall)
                }
            }
        }
    }
}

expect fun isVirtualDeviceInstalled(): Boolean
expect suspend fun uninstallVirtualDevice()

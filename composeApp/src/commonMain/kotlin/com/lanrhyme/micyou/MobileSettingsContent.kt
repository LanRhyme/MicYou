package com.lanrhyme.micyou

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.theme.ExpressiveListItem
import com.lanrhyme.micyou.theme.ExpressiveSettingsBoxItem
import com.lanrhyme.micyou.theme.ExpressiveSettingsDropdownItem
import com.lanrhyme.micyou.theme.ExpressiveSettingsSwitchItem
import com.lanrhyme.micyou.theme.PaletteStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * M3 Expressive 风格的手机端设置页面
 * 使用单层列表卡片容器，第一项顶部大圆角，最后一项底部大圆角，中间项之间有空隙
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSettingsPage(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    hazeState: HazeState?
) {
    val strings = LocalAppStrings.current
    val state by viewModel.uiState.collectAsState()
    val isDarkTheme = isDarkThemeActive(state.themeMode)
    val platform = getPlatform()

    val backgroundColor = MaterialTheme.colorScheme.background
    val topBarBackgroundColor = backgroundColor.copy(alpha = 0.8f)
    // 独立的 HazeState 用于顶部导航栏毛玻璃效果
    val topBarHazeState = rememberHazeState()

    Box(modifier = Modifier.fillMaxSize()) {
        // 内容区域 - 作为 hazeSource（与 TopAppBar 的 hazeEffect 是兄弟关系）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (state.backgroundSettings.hasCustomBackground) Color.Transparent
                    else backgroundColor
                )
                .hazeSource(state = topBarHazeState)
        ) {
        // 内容列表 - 使用 contentPadding 让内容从 TopAppBar 下方开始，滚动时可穿过半透明导航栏
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 64.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
            // General Section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(height = 18.dp, width = 5.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.generalSection,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                ExpressiveGeneralSettings(viewModel, hazeState)
            }

            // Appearance Section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(height = 18.dp, width = 5.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.appearanceSection,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                ExpressiveAppearanceSettings(viewModel, hazeState)
            }

            // Audio Section (Android only for mobile)
            if (platform.type == PlatformType.Android) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(height = 18.dp, width = 5.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            strings.audioSection,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    ExpressiveAudioSettings(viewModel, hazeState)
                }
            }

            // Plugins Section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(height = 18.dp, width = 5.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.pluginsSection,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                ExpressivePluginSettings(viewModel, hazeState)
            }

            // About Section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(height = 18.dp, width = 5.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.aboutSection,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                ExpressiveAboutSettings(viewModel, hazeState)
            }

            // 底部额外间距
            item {
                Spacer(Modifier.height(16.dp))
            }
        }
        }

        // 顶部导航栏 - 0.8 不透明度 + haze 毛玻璃效果（始终启用，与内容区域是兄弟关系）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .hazeEffect(
                    state = topBarHazeState,
                    style = HazeStyle(
                        backgroundColor = topBarBackgroundColor,
                        tints = listOf(HazeTint(color = topBarBackgroundColor))
                    )
                )
        ) {
            TopAppBar(
                title = {
                    Text(
                        strings.settingsTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.close)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    }
}

/**
 * Expressive 风格的通用设置部分
 */
@Composable
private fun ExpressiveGeneralSettings(viewModel: MainViewModel, hazeState: HazeState?) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    val platform = getPlatform()

    val cardOpacity = state.backgroundSettings.cardOpacity
    val enableHaze = state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground
    val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = baseContainerColor.copy(alpha = cardOpacity)

    // 收集所有设置项
    val items = mutableListOf<@Composable (isFirst: Boolean, isLast: Boolean) -> Unit>()

    // 语言选择
    items.add { isFirst, isLast ->
        ExpressiveSettingsDropdownItem(
            headline = strings.languageLabel,
            selected = state.language,
            options = AppLanguage.entries.toList(),
            labelProvider = { it.label },
            onSelect = { viewModel.setLanguage(it) },
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // Android 专属设置
    if (platform.type == PlatformType.Android) {
        items.add { isFirst, isLast ->
            ExpressiveSettingsSwitchItem(
                headline = strings.enableStreamingNotificationLabel,
                checked = state.enableStreamingNotification,
                onCheckedChange = { viewModel.setEnableStreamingNotification(it) },
                isFirst = isFirst,
                isLast = isLast,
                containerColor = containerColor,
                hazeState = hazeState,
                enableHaze = enableHaze
            )
        }

        items.add { isFirst, isLast ->
            ExpressiveSettingsSwitchItem(
                headline = strings.keepScreenOnLabel,
                supporting = strings.keepScreenOnDesc,
                checked = state.keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) },
                isFirst = isFirst,
                isLast = isLast,
                containerColor = containerColor,
                hazeState = hazeState,
                enableHaze = enableHaze
            )
        }
    }

    // 全平台通用设置
    items.add { isFirst, isLast ->
        ExpressiveSettingsSwitchItem(
            headline = strings.autoCheckUpdateLabel,
            supporting = strings.autoCheckUpdateDesc,
            checked = state.autoCheckUpdate,
            onCheckedChange = { viewModel.setAutoCheckUpdate(it) },
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    items.add { isFirst, isLast ->
        ExpressiveSettingsSwitchItem(
            headline = strings.mirrorDownloadLabel,
            supporting = strings.mirrorDownloadDesc,
            checked = state.useMirrorDownload,
            onCheckedChange = { viewModel.setUseMirrorDownload(it) },
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // 渲染设置项
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.size - 1
            item(isFirst, isLast)
        }
    }
}

/**
 * Expressive 风格的外观设置部分
 */
@Composable
private fun ExpressiveAppearanceSettings(viewModel: MainViewModel, hazeState: HazeState?) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    val platform = getPlatform()

    val cardOpacity = state.backgroundSettings.cardOpacity
    val enableHaze = state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground
    val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = baseContainerColor.copy(alpha = cardOpacity)

    val seedColors = listOf(
        0xFF1565C0L, // Ocean Blue
        0xFF6750A4L, // M3 Purple
        0xFFE91E63L, // Rose Pink
        0xFF2E7D32L, // Forest Green
        0xFFFF5722L, // Sunset Orange
        0xFF00695CL, // Deep Teal
        0xFF283593L, // Midnight Indigo
        0xFF7B1FA2L  // Lavender Violet
    )

    // 收集所有设置项
    val items = mutableListOf<@Composable (isFirst: Boolean, isLast: Boolean) -> Unit>()

    // 主题选择 - 复杂内容
    items.add { isFirst, isLast ->
        ExpressiveSettingsBoxItem(
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Text(strings.themeLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
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

    // 动态取色
    if (platform.type == PlatformType.Android || isDynamicColorSupported()) {
        items.add { isFirst, isLast ->
            ExpressiveSettingsSwitchItem(
                headline = strings.useDynamicColorLabel,
                supporting = strings.useDynamicColorDesc,
                checked = state.useDynamicColor,
                onCheckedChange = { viewModel.setUseDynamicColor(it) },
                isFirst = isFirst,
                isLast = isLast,
                containerColor = containerColor,
                hazeState = hazeState,
                enableHaze = enableHaze
            )
        }
    }

    // OLED 纯黑
    items.add { isFirst, isLast ->
        ExpressiveSettingsSwitchItem(
            headline = strings.oledPureBlackLabel,
            supporting = strings.oledPureBlackDesc,
            checked = state.oledPureBlack,
            onCheckedChange = { viewModel.setOledPureBlack(it) },
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // 主题颜色选择 - 复杂内容
    items.add { isFirst, isLast ->
        ExpressiveSettingsBoxItem(
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Text(strings.themeColorLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            val isSeedColorEnabled = !state.useDynamicColor
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
            if (state.useDynamicColor) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = strings.dynamicColorEnabledHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Palette Style - 复杂内容
    items.add { isFirst, isLast ->
        ExpressiveSettingsBoxItem(
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Text(strings.expressive.paletteStyleLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(strings.expressive.paletteStyleDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PaletteStyle.entries) { style ->
                    FilterChip(
                        selected = state.paletteStyle == style,
                        onClick = { viewModel.setPaletteStyle(style) },
                        label = {
                            Text(style.name)
                        },
                        leadingIcon = {
                            if (state.paletteStyle == style) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) else null
                        }
                    )
                }
            }
        }
    }

    // Expressive Shapes
    items.add { isFirst, isLast ->
        ExpressiveSettingsSwitchItem(
            headline = strings.expressive.useExpressiveShapesLabel,
            supporting = strings.expressive.useExpressiveShapesDesc,
            checked = state.useExpressiveShapes,
            onCheckedChange = { viewModel.setUseExpressiveShapes(it) },
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // Expressive Typography
    items.add { isFirst, isLast ->
        ExpressiveSettingsSwitchItem(
            headline = strings.expressive.useExpressiveTypographyLabel,
            supporting = strings.expressive.useExpressiveTypographyDesc,
            checked = state.useExpressiveTypography,
            onCheckedChange = { viewModel.setUseExpressiveTypography(it) },
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // 可视化样式 - 复杂内容
    items.add { isFirst, isLast ->
        ExpressiveSettingsBoxItem(
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Text(strings.visualizerStyleLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
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

    // 背景设置 - 复杂内容
    items.add { isFirst, isLast ->
        ExpressiveSettingsBoxItem(
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Text(strings.backgroundSettingsLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

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
                Spacer(Modifier.height(8.dp))
                Text("${strings.backgroundBrightnessLabel}: ${(state.backgroundSettings.brightness * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = state.backgroundSettings.brightness,
                    onValueChange = { viewModel.setBackgroundBrightness(it) },
                    valueRange = 0f..1f
                )

                Text("${strings.backgroundBlurLabel}: ${state.backgroundSettings.blurRadius.toInt()}px", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = state.backgroundSettings.blurRadius,
                    onValueChange = { viewModel.setBackgroundBlur(it) },
                    valueRange = 0f..50f
                )

                Text("${strings.cardOpacityLabel}: ${(state.backgroundSettings.cardOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = state.backgroundSettings.cardOpacity,
                    onValueChange = { viewModel.setCardOpacity(it) },
                    valueRange = 0f..1f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(strings.enableHazeEffectLabel, style = MaterialTheme.typography.bodySmall)
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

    // 渲染设置项
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.size - 1
            item(isFirst, isLast)
        }
    }
}

/**
 * Expressive 风格的音频设置部分 (Android)
 */
@Composable
private fun ExpressiveAudioSettings(viewModel: MainViewModel, hazeState: HazeState?) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current

    val cardOpacity = state.backgroundSettings.cardOpacity
    val enableHaze = state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground
    val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = baseContainerColor.copy(alpha = cardOpacity)

    // 收集所有设置项
    val items = mutableListOf<@Composable (isFirst: Boolean, isLast: Boolean) -> Unit>()

    // 自动配置
    items.add { isFirst, isLast ->
        ExpressiveListItem(
            isFirst = isFirst,
            isLast = isLast,
            onClick = { viewModel.setAutoConfig(!state.isAutoConfig) },
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        strings.autoConfigLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.autoConfigDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.isAutoConfig,
                    onCheckedChange = null // Handled by row click
                )
            }
        }
    }

    val manualSettingsEnabled = !state.isAutoConfig

    // 采样率
    items.add { isFirst, isLast ->
        ExpressiveAudioDropdownItem(
            headline = strings.sampleRateLabel,
            selected = "${state.sampleRate.value} Hz",
            options = SampleRate.entries.map { "${it.value} Hz" },
            onSelect = { index -> viewModel.setSampleRate(SampleRate.entries[index]) },
            enabled = manualSettingsEnabled,
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // 声道数
    items.add { isFirst, isLast ->
        ExpressiveAudioDropdownItem(
            headline = strings.channelCountLabel,
            selected = state.channelCount.label,
            options = ChannelCount.entries.map { it.label },
            onSelect = { index -> viewModel.setChannelCount(ChannelCount.entries[index]) },
            enabled = manualSettingsEnabled,
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // 音频格式
    items.add { isFirst, isLast ->
        ExpressiveAudioDropdownItem(
            headline = strings.audioFormatLabel,
            selected = state.audioFormat.label,
            options = AudioFormat.entries.map { it.label },
            onSelect = { index -> viewModel.setAudioFormat(AudioFormat.entries[index]) },
            enabled = manualSettingsEnabled,
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // 音频源
    items.add { isFirst, isLast ->
        ExpressiveAudioSourceItem(
            viewModel = viewModel,
            isFirst = isFirst,
            isLast = isLast,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        )
    }

    // 渲染设置项
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.size - 1
            item(isFirst, isLast)
        }
    }
}

/**
 * 音频下拉选择项
 */
@Composable
private fun ExpressiveAudioDropdownItem(
    headline: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    ExpressiveListItem(
        isFirst = isFirst,
        isLast = isLast,
        onClick = if (enabled) { { expanded = true } } else null,
        containerColor = containerColor,
        hazeState = hazeState,
        enableHaze = enableHaze
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.weight(1f).padding(end = 16.dp)
            )
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = selected,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { onSelect(index); expanded = false },
                            trailingIcon = {
                                if (option == selected) Icon(Icons.Default.Check, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 音频源选择项
 */
@Composable
private fun ExpressiveAudioSourceItem(
    viewModel: MainViewModel,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }

    val audioSourceOptions = getAudioSourceOptions()
    val currentSource = audioSourceOptions.find { it.name == state.androidAudioSourceName } ?: audioSourceOptions.firstOrNull()

    ExpressiveListItem(
        isFirst = isFirst,
        isLast = isLast,
        onClick = { expanded = true },
        containerColor = containerColor,
        hazeState = hazeState,
        enableHaze = enableHaze
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = strings.audioSourceLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 16.dp)
            )
            if (audioSourceOptions.isNotEmpty() && currentSource != null) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = currentSource.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        audioSourceOptions.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.label) },
                                onClick = { viewModel.setAndroidAudioSource(source.name); expanded = false },
                                trailingIcon = {
                                    if (currentSource == source) Icon(Icons.Default.Check, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Expressive 风格的插件设置部分
 */
@Composable
private fun ExpressivePluginSettings(viewModel: MainViewModel, hazeState: HazeState?) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current

    val cardOpacity = state.backgroundSettings.cardOpacity
    val enableHaze = state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground
    val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = baseContainerColor.copy(alpha = cardOpacity)

    // 使用单层卡片包裹插件设置内容
    ExpressiveSettingsBoxItem(
        isSingle = true,
        containerColor = containerColor,
        hazeState = hazeState,
        enableHaze = enableHaze
    ) {
        Text(strings.pluginsSection, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        PluginSettingsContent(viewModel, state.backgroundSettings.cardOpacity)
    }
}

/**
 * Expressive 风格的关于设置部分
 */
@Composable
private fun ExpressiveAboutSettings(viewModel: MainViewModel, hazeState: HazeState?) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    val uriHandler = LocalUriHandler.current
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showContributorsDialog by remember { mutableStateOf(false) }

    val cardOpacity = state.backgroundSettings.cardOpacity
    val enableHaze = state.backgroundSettings.enableHazeEffect && state.backgroundSettings.hasCustomBackground
    val baseContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val containerColor = baseContainerColor.copy(alpha = cardOpacity)

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

    // 收集所有设置项
    val items = mutableListOf<@Composable (isFirst: Boolean, isLast: Boolean) -> Unit>()

    // 开发者
    items.add { isFirst, isLast ->
        ExpressiveListItem(
            isFirst = isFirst,
            isLast = isLast,
            onClick = null,
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Person, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.developerLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("LanRhyme、ChinsaaWei", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // GitHub 仓库
    items.add { isFirst, isLast ->
        ExpressiveListItem(
            isFirst = isFirst,
            isLast = isLast,
            onClick = { uriHandler.openUri("https://github.com/LanRhyme/MicYou") },
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Language, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.githubRepoLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "https://github.com/LanRhyme/MicYou",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    )
                }
            }
        }
    }

    // 贡献者
    items.add { isFirst, isLast ->
        ExpressiveListItem(
            isFirst = isFirst,
            isLast = isLast,
            onClick = { showContributorsDialog = true },
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.People, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.contributorsLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.contributorsDesc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 版本
    items.add { isFirst, isLast ->
        ExpressiveListItem(
            isFirst = isFirst,
            isLast = isLast,
            onClick = { viewModel.checkUpdateManual() },
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Info, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(strings.versionLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(getAppVersion(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { viewModel.checkUpdateManual() }) {
                    Text(strings.checkUpdate)
                }
            }
        }
    }

    // 开源许可证
    items.add { isFirst, isLast ->
        ExpressiveListItem(
            isFirst = isFirst,
            isLast = isLast,
            onClick = { showLicenseDialog = true },
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Description, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.openSourceLicense, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.viewLibraries, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 导出日志
    items.add { isFirst, isLast ->
        ExpressiveListItem(
            isFirst = isFirst,
            isLast = isLast,
            onClick = {
                viewModel.exportLog { path ->
                    if (path != null) {
                        viewModel.showSnackbar("${strings.logExported}: $path")
                    }
                }
            },
            containerColor = containerColor,
            hazeState = hazeState,
            enableHaze = enableHaze
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Rounded.TextSnippet, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.exportLog, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.exportLogDesc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // 渲染设置项
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.size - 1
            item(isFirst, isLast)
        }
    }

    // 底部软件介绍卡片
    Spacer(Modifier.height(12.dp))
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        ),
        shape = MaterialTheme.shapes.large
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
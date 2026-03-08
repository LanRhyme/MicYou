package com.lanrhyme.micyou

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.icon_compass
import micyou.composeapp.generated.resources.icon_creative
import micyou.composeapp.generated.resources.icon_file
import micyou.composeapp.generated.resources.icon_microphone
import micyou.composeapp.generated.resources.icon_palette
import micyou.composeapp.generated.resources.icon_planet
import micyou.composeapp.generated.resources.icon_settings
import micyou.composeapp.generated.resources.icon_star_fall
import micyou.composeapp.generated.resources.icon_star_fall_mini
import org.jetbrains.compose.resources.painterResource

enum class SettingsSection {
    General,
    Appearance,
    Audio,
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

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            CustomBackground(
                settings = state.backgroundSettings,
                modifier = Modifier.fillMaxSize()
            )
            if (platform.type == PlatformType.Desktop) {
                DesktopLayout(viewModel, onClose)
            } else {
                MobileLayout(viewModel, onClose)
            }
        }
    }
}

@Composable
fun DesktopLayout(viewModel: MainViewModel, onClose: () -> Unit) {
    var currentSection by remember { mutableStateOf(SettingsSection.General) }
    val strings = LocalAppStrings.current
    val state by viewModel.uiState.collectAsState()
    val cardOpacity = state.backgroundSettings.cardOpacity
    
    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.width(220.dp).fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.9f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    Text(
                        strings.settingsTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                }
                
                item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
                
                items(SettingsSection.entries.toList()) { section ->
                    val icon = when (section) {
                        SettingsSection.General -> painterResource(Res.drawable.icon_settings)
                        SettingsSection.Appearance -> painterResource(Res.drawable.icon_palette)
                        SettingsSection.Audio -> painterResource(Res.drawable.icon_microphone)
                        SettingsSection.About -> painterResource(Res.drawable.icon_compass)
                    }
                    val isSelected = currentSection == section
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { currentSection = section }
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            )
                            .animateContentSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = icon,
                            contentDescription = section.getLabel(strings),
                            modifier = Modifier.size(22.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            section.getLabel(strings),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        VerticalDivider()
        
        Surface(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity * 0.7f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(currentSection.getLabel(strings), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                         SettingsContent(currentSection, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun MobileLayout(viewModel: MainViewModel, onClose: () -> Unit) {
    val strings = LocalAppStrings.current
    val state by viewModel.uiState.collectAsState()
    val cardOpacity = state.backgroundSettings.cardOpacity
    
    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.settingsTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, strings.close)
                }
            }
        }
        
        SettingsSection.entries.forEach { section ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = cardOpacity)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(section.getLabel(strings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        SettingsContent(section, viewModel)
                    }
                }
            }
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.languageLabel) },
                            trailingContent = {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expanded = true }) { 
                                        Text(state.language.label) 
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        AppLanguage.entries.forEach { lang ->
                                            DropdownMenuItem(
                                                text = { Text(lang.label) },
                                                onClick = {
                                                    viewModel.setLanguage(lang)
                                                    expanded = false
                                                },
                                                trailingIcon = {
                                                    if (state.language == lang) {
                                                        Icon(Icons.Default.Check, contentDescription = null)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }

                    if (platform.type == PlatformType.Android) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableStreamingNotificationLabel) },
                                trailingContent = {
                                    Switch(
                                        checked = state.enableStreamingNotification,
                                        onCheckedChange = { viewModel.setEnableStreamingNotification(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setEnableStreamingNotification(!state.enableStreamingNotification) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.keepScreenOnLabel) },
                                supportingContent = { Text(strings.keepScreenOnDesc) },
                                trailingContent = {
                                    Switch(
                                        checked = state.keepScreenOn,
                                        onCheckedChange = { viewModel.setKeepScreenOn(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setKeepScreenOn(!state.keepScreenOn) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }

                    if (platform.type == PlatformType.Desktop) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.autoStartLabel) },
                                supportingContent = { Text(strings.autoStartDesc) },
                                trailingContent = {
                                    Switch(
                                        checked = state.autoStart,
                                        onCheckedChange = { viewModel.setAutoStart(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setAutoStart(!state.autoStart) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.closeActionLabel) },
                                trailingContent = {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { expanded = true }) {
                                            Text(when (state.closeAction) {
                                                CloseAction.Prompt -> strings.closeActionPrompt
                                                CloseAction.Minimize -> strings.closeActionMinimize
                                                CloseAction.Exit -> strings.closeActionExit
                                            })
                                        }
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            CloseAction.entries.forEach { action ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(when (action) {
                                                            CloseAction.Prompt -> strings.closeActionPrompt
                                                            CloseAction.Minimize -> strings.closeActionMinimize
                                                            CloseAction.Exit -> strings.closeActionExit
                                                        })
                                                    },
                                                    onClick = {
                                                        viewModel.setCloseAction(action)
                                                        expanded = false
                                                    },
                                                    trailingIcon = {
                                                        if (state.closeAction == action) {
                                                            Icon(Icons.Default.Check, contentDescription = null)
                                                        }
                                                    }
                                                )
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.pocketModeLabel) },
                                supportingContent = { Text(strings.pocketModeDesc) },
                                trailingContent = {
                                    Switch(
                                        checked = state.pocketMode,
                                        onCheckedChange = { viewModel.setPocketMode(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setPocketMode(!state.pocketMode) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.floatingWindowLabel) },
                                supportingContent = { Text(strings.floatingWindowDesc) },
                                trailingContent = {
                                    Switch(
                                        checked = state.floatingWindowEnabled,
                                        onCheckedChange = { viewModel.setFloatingWindowEnabled(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setFloatingWindowEnabled(!state.floatingWindowEnabled) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            }
            SettingsSection.Appearance -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
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

                    if (platform.type == PlatformType.Android) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.useDynamicColorLabel) },
                                trailingContent = {
                                    Switch(
                                        checked = state.useDynamicColor,
                                        onCheckedChange = { viewModel.setUseDynamicColor(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setUseDynamicColor(!state.useDynamicColor) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.oledPureBlackLabel) },
                            supportingContent = { Text(strings.oledPureBlackDesc) },
                            trailingContent = {
                                Switch(
                                    checked = state.oledPureBlack,
                                    onCheckedChange = { viewModel.setOledPureBlack(it) }
                                )
                            },
                            modifier = Modifier.clickable { viewModel.setOledPureBlack(!state.oledPureBlack) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.themeColorLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            val isSeedColorEnabled = !state.useDynamicColor
                            ColorSelectorWithPicker(
                                selectedColor = state.seedColor,
                                presetColors = seedColors,
                                onColorSelected = { viewModel.setSeedColor(it) },
                                enabled = isSeedColorEnabled,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
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
                            .clip(RoundedCornerShape(12.dp))
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
                                .clip(RoundedCornerShape(12.dp))
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
                                .clip(RoundedCornerShape(12.dp))
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
                                            shape = RoundedCornerShape(16.dp)
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
                                .clip(RoundedCornerShape(12.dp))
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
                                            shape = RoundedCornerShape(16.dp)
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
                                .clip(RoundedCornerShape(12.dp))
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
                                            shape = RoundedCornerShape(16.dp)
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.androidAudioProcessingLabel) },
                                supportingContent = { Text(strings.androidAudioProcessingDesc) },
                                trailingContent = {
                                    Switch(
                                        checked = state.enableNS || state.enableAGC,
                                        onCheckedChange = { viewModel.setAndroidAudioProcessing(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setAndroidAudioProcessing(!(state.enableNS || state.enableAGC)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var showApplied by remember { mutableStateOf(false) }
                        LaunchedEffect(state.audioConfigRevision) {
                            if (state.audioConfigRevision > 0) {
                                showApplied = true
                                delay(1200)
                                showApplied = false
                            }
                        }
                        if (showApplied) {
                            Text(
                                strings.audioConfigAppliedLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableNsLabel) },
                                trailingContent = { Switch(checked = state.enableNS, onCheckedChange = { viewModel.setEnableNS(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableNS(!state.enableNS) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableNS) {
                            var showNsTypeHelp by remember { mutableStateOf(false) }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                            ) {
                                ListItem(
                                    headlineContent = { Text(strings.nsTypeLabel) },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { showNsTypeHelp = true }) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = "降噪算法说明",
                                                )
                                            }

                                            var expanded by remember { mutableStateOf(false) }
                                            Box {
                                                TextButton(onClick = { expanded = true }) { Text(state.nsType.name) }
                                                DropdownMenu(
                                                    expanded = expanded,
                                                    onDismissRequest = { expanded = false },
                                                    shape = RoundedCornerShape(16.dp)
                                                ) {
                                                    NoiseReductionType.entries.forEach { type ->
                                                        DropdownMenuItem(text = { Text(type.name) }, onClick = { viewModel.setNsType(type); expanded = false })
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }

                            if (showNsTypeHelp) {
                                NoiseReductionHelpPopup(onDismiss = { showNsTypeHelp = false })
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableAgcLabel) },
                                trailingContent = { Switch(checked = state.enableAGC, onCheckedChange = { viewModel.setEnableAGC(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableAGC(!state.enableAGC) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableAGC) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
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
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableVadLabel) },
                                trailingContent = { Switch(checked = state.enableVAD, onCheckedChange = { viewModel.setEnableVAD(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableVAD(!state.enableVAD) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableVAD) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
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
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            ListItem(
                                headlineContent = { Text(strings.enableDereverbLabel) },
                                trailingContent = { Switch(checked = state.enableDereverb, onCheckedChange = { viewModel.setEnableDereverb(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableDereverb(!state.enableDereverb) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        if (state.enableDereverb) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("${strings.dereverbLevelLabel}: ${((state.dereverbLevel * 100).toInt()) / 100f}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = state.dereverbLevel,
                                        onValueChange = { viewModel.setDereverbLevel(it) },
                                        valueRange = 0.0f..1.0f
                                    )
                                }
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(strings.amplificationLabel, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${strings.amplificationMultiplierLabel}: ${((state.amplification * 10).toInt()) / 10f}x", style = MaterialTheme.typography.bodySmall)
                                    
                                    var textValue by remember(state.amplification) { mutableStateOf(((state.amplification * 10).toInt() / 10f).toString()) }
                                    
                                    BasicTextField(
                                        value = textValue,
                                        onValueChange = { 
                                            textValue = it
                                            it.toFloatOrNull()?.let { val floatVal = it.coerceIn(0f, 60f); viewModel.setAmplification(floatVal) }
                                        },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.End
                                        ),
                                        modifier = Modifier
                                            .width(60.dp)
                                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                    )
                                }
                                Slider(
                                    value = state.amplification,
                                    onValueChange = { viewModel.setAmplification(it) },
                                    valueRange = 0.0f..60.0f
                                )
                            }
                        }
                    }
                }
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.developerLabel) },
                            supportingContent = { Text("LanRhyme、ChinsaaWei") },
                            leadingContent = { Icon(painterResource(Res.drawable.icon_star_fall_mini), null,modifier = Modifier.size(24.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
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
                            leadingContent = { Icon(painterResource(Res.drawable.icon_planet), null,modifier = Modifier.size(24.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.contributorsLabel) },
                            supportingContent = { Text(strings.contributorsDesc) },
                            leadingContent = { Icon(painterResource(Res.drawable.icon_star_fall), null,modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.clickable { showContributorsDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.versionLabel) },
                            supportingContent = { Text(getAppVersion()) },
                            leadingContent = { Icon(painterResource(Res.drawable.icon_compass), null,modifier = Modifier.size(24.dp)) },
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.openSourceLicense) },
                            supportingContent = { Text(strings.viewLibraries) },
                            leadingContent = { Icon(painterResource(Res.drawable.icon_creative), null,modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.clickable { showLicenseDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f))
                    ) {
                        ListItem(
                            headlineContent = { Text(strings.exportLog) },
                            supportingContent = { Text(strings.exportLogDesc) },
                            leadingContent = { Icon(painterResource(Res.drawable.icon_file), null,modifier = Modifier.size(24.dp)) },
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
                    shape = RoundedCornerShape(12.dp)
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
            shape = RoundedCornerShape(16.dp),
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
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            if (recommendation.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (isRecommended) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
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

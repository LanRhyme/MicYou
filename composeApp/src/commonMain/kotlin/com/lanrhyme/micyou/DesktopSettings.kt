package com.lanrhyme.micyou

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

enum class SettingsSection(val label: String, val icon: ImageVector) {
    General("常规", Icons.Default.Settings),
    Appearance("外观", Icons.Default.Palette),
    Audio("音频", Icons.Default.Mic),
    About("关于", Icons.Default.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopSettings(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val platform = getPlatform()
    
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        if (platform.type == PlatformType.Desktop) {
            DesktopLayout(viewModel, onClose)
        } else {
            MobileLayout(viewModel, onClose)
        }
    }
}

@Composable
fun DesktopLayout(viewModel: MainViewModel, onClose: () -> Unit) {
    var currentSection by remember { mutableStateOf(SettingsSection.General) }
    
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            Spacer(Modifier.weight(1f))
            
            SettingsSection.entries.forEach { section ->
                NavigationRailItem(
                    selected = currentSection == section,
                    onClick = { currentSection = section },
                    icon = { Icon(section.icon, contentDescription = section.label) },
                    label = { Text(section.label) }
                )
            }
            Spacer(Modifier.weight(1f))
        }
        
        VerticalDivider()
        
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Add a title for the section
            Column {
                Text(currentSection.label, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                
                // Use a scrollable column for content in case it overflows
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
    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close")
                }
            }
        }
        
        SettingsSection.entries.forEach { section ->
            // Skip "General" on mobile if it has no content (AutoStart is desktop only)
            if (section == SettingsSection.General) return@forEach

            item {
                Text(section.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                SettingsContent(section, viewModel)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(section: SettingsSection, viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val platform = getPlatform()

    // 预设种子颜色
    val seedColors = listOf(
        0xFF6750A4L, // Purple (Default)
        0xFFB3261EL, // Red
        0xFFFBC02DL, // Yellow
        0xFF388E3CL, // Green
        0xFF006C51L, // Teal
        0xFF2196F3L, // Blue
        0xFFE91E63L  // Pink
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (section) {
            SettingsSection.General -> {
                if (platform.type == PlatformType.Desktop) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            ListItem(
                                headlineContent = { Text("自动串流") },
                                supportingContent = { Text("应用启动时自动开始串流") },
                                trailingContent = {
                                    Switch(
                                        checked = state.autoStart,
                                        onCheckedChange = { viewModel.setAutoStart(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setAutoStart(!state.autoStart) }
                            )
                        }
                    }
                } else {
                    Text("暂无通用设置")
                }
            }
            SettingsSection.Appearance -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                     Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                         Text("主题模式", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                         Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                             ThemeMode.entries.forEach { mode ->
                                 FilterChip(
                                     selected = state.themeMode == mode,
                                     onClick = { viewModel.setThemeMode(mode) },
                                     label = { Text(mode.name) },
                                     leadingIcon = {
                                         if (state.themeMode == mode) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) else null
                                     }
                                 )
                             }
                         }
                         
                         HorizontalDivider()

                         Text("主题颜色", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                         Row(
                             horizontalArrangement = Arrangement.spacedBy(12.dp),
                             modifier = Modifier.fillMaxWidth()
                         ) {
                             seedColors.forEach { colorHex ->
                                 val color = Color(colorHex.toInt())
                                 Box(
                                     modifier = Modifier
                                         .size(40.dp)
                                         .background(color, CircleShape)
                                         .clickable { viewModel.setSeedColor(colorHex) }
                                         .then(
                                             if (state.seedColor == colorHex) {
                                                 Modifier.padding(2.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape).padding(2.dp).background(color, CircleShape)
                                             } else Modifier
                                         )
                                 )
                             }
                         }
                     }
                }
            }
            SettingsSection.Audio -> {
                if (platform.type == PlatformType.Android) {
                    // Android Audio Params
                     Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column {
                            ListItem(
                                headlineContent = { Text("采样率") },
                                trailingContent = {
                                     var expanded by remember { mutableStateOf(false) }
                                     Box {
                                         TextButton(onClick = { expanded = true }) { Text("${state.sampleRate.value} Hz") }
                                         DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                             SampleRate.entries.forEach { rate ->
                                                 DropdownMenuItem(text = { Text("${rate.value} Hz") }, onClick = { viewModel.setSampleRate(rate); expanded = false })
                                             }
                                         }
                                     }
                                }
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("通道数") },
                                trailingContent = {
                                     var expanded by remember { mutableStateOf(false) }
                                     Box {
                                         TextButton(onClick = { expanded = true }) { Text(state.channelCount.label) }
                                         DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                             ChannelCount.entries.forEach { count ->
                                                 DropdownMenuItem(text = { Text(count.label) }, onClick = { viewModel.setChannelCount(count); expanded = false })
                                             }
                                         }
                                     }
                                }
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("音频格式") },
                                trailingContent = {
                                     var expanded by remember { mutableStateOf(false) }
                                     Box {
                                         TextButton(onClick = { expanded = true }) { Text(state.audioFormat.label) }
                                         DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                             AudioFormat.entries.forEach { format ->
                                                 DropdownMenuItem(text = { Text(format.label) }, onClick = { viewModel.setAudioFormat(format); expanded = false })
                                             }
                                         }
                                     }
                                }
                            )
                        }
                     }
                } else {
                    // Desktop Audio Processing
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                         Column(modifier = Modifier.padding(8.dp)) {
                            // Noise Suppression
                            ListItem(
                                headlineContent = { Text("降噪 (Noise Suppression)") },
                                trailingContent = { Switch(checked = state.enableNS, onCheckedChange = { viewModel.setEnableNS(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableNS(!state.enableNS) }
                            )
                            if (state.enableNS) {
                                 Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    NoiseReductionType.entries.filter { it != NoiseReductionType.None }.forEach { type ->
                                        FilterChip(
                                            selected = state.nsType == type,
                                            onClick = { viewModel.setNsType(type) },
                                            label = { Text(type.label) }
                                        )
                                    }
                                }
                            }
                            
                            HorizontalDivider()
                            
                            // AGC
                            ListItem(
                                headlineContent = { Text("自动增益控制 (AGC)") },
                                trailingContent = { Switch(checked = state.enableAGC, onCheckedChange = { viewModel.setEnableAGC(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableAGC(!state.enableAGC) }
                            )
                            if (state.enableAGC) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text("目标电平: ${state.agcTargetLevel}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = state.agcTargetLevel.toFloat(),
                                        onValueChange = { viewModel.setAgcTargetLevel(it.toInt()) },
                                        valueRange = 8000f..65535f
                                    )
                                }
                            }
                            
                            HorizontalDivider()

                            // VAD
                            ListItem(
                                headlineContent = { Text("语音活动检测 (VAD)") },
                                trailingContent = { Switch(checked = state.enableVAD, onCheckedChange = { viewModel.setEnableVAD(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableVAD(!state.enableVAD) }
                            )
                            if (state.enableVAD) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text("阈值: ${state.vadThreshold}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = state.vadThreshold.toFloat(),
                                        onValueChange = { viewModel.setVadThreshold(it.toInt()) },
                                        valueRange = 0f..100f
                                    )
                                }
                            }
                            
                            HorizontalDivider()

                            // Dereverb
                            ListItem(
                                headlineContent = { Text("去混响 (Dereverb)") },
                                trailingContent = { Switch(checked = state.enableDereverb, onCheckedChange = { viewModel.setEnableDereverb(it) }) },
                                modifier = Modifier.clickable { viewModel.setEnableDereverb(!state.enableDereverb) }
                            )
                            if (state.enableDereverb) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text("强度: ${((state.dereverbLevel * 100).toInt()) / 100f}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = state.dereverbLevel,
                                        onValueChange = { viewModel.setDereverbLevel(it) },
                                        valueRange = 0.0f..1.0f
                                    )
                                }
                            }
                            
                            HorizontalDivider()

                            // Amplification
                            ListItem(
                                headlineContent = { Text("信号放大 (Amplification)") },
                                supportingContent = {
                                    Column {
                                        Text("倍数: ${((state.amplification * 10).toInt()) / 10f}x", style = MaterialTheme.typography.bodySmall)
                                        Slider(
                                            value = state.amplification,
                                            onValueChange = { viewModel.setAmplification(it) },
                                            valueRange = 0.0f..30.0f
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            SettingsSection.About -> {
                val uriHandler = LocalUriHandler.current
                var showLicenseDialog by remember { mutableStateOf(false) }

                if (showLicenseDialog) {
                    AlertDialog(
                        onDismissRequest = { showLicenseDialog = false },
                        title = { Text("Open Source Libraries and Licenses") },
                        text = {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    Text("MicYou 基于 AndroidMic 项目开发。", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(8.dp))
                                }
                                item {
                                    Text("AndroidMic", style = MaterialTheme.typography.titleSmall)
                                    Text("https://github.com/mhuth/AndroidMic", style = MaterialTheme.typography.bodySmall)
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
                                Text("关闭")
                            }
                        }
                    )
                }
                
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column {
                        ListItem(
                            headlineContent = { Text("开发者") },
                            supportingContent = { Text("LanRhyme") },
                            leadingContent = { Icon(Icons.Default.Person, null) }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Github 仓库") },
                            supportingContent = { 
                                Text(
                                    "https://github.com/LanRhyme/MicYou",
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable { uriHandler.openUri("https://github.com/LanRhyme/MicYou") }
                                ) 
                            },
                            leadingContent = { Icon(Icons.Default.Code, null) }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("版本") },
                            supportingContent = { Text("1.0.0") },
                            leadingContent = { Icon(Icons.Default.Info, null) }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("开源许可") },
                            supportingContent = { Text("查看使用的开源库") },
                            leadingContent = { Icon(Icons.Default.Description, null) },
                            modifier = Modifier.clickable { showLicenseDialog = true }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                     Column(modifier = Modifier.padding(16.dp)) {
                        Text("软件介绍", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "MicYou 是一款开源的麦克风工具，可以将您的 Android 设备变成电脑的高质量麦克风，本软件基于 AndroidMic 进行开发，支持 Wi-Fi (TCP)、蓝牙 和 USB 连接，提供低延迟的音频传输体验。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                     }
                }
            }
        }
    }
}
package com.lanrhyme.micyou

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
    
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            Spacer(Modifier.weight(1f))
            
            SettingsSection.entries.forEach { section ->
                NavigationRailItem(
                    selected = currentSection == section,
                    onClick = { currentSection = section },
                    icon = { Icon(section.icon, contentDescription = section.getLabel(strings)) },
                    label = { Text(section.getLabel(strings)) }
                )
            }
            Spacer(Modifier.weight(1f))
        }
        
        VerticalDivider()
        
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Add a title for the section
            Column {
                Text(currentSection.getLabel(strings), style = MaterialTheme.typography.headlineMedium)
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
    val strings = LocalAppStrings.current
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
            // Skip "General" on mobile if it has no content (AutoStart is desktop only)
            // But now we have Language setting, so check if platform is Android AND section is General AND no other settings?
            // Actually Language setting is for both. So General section is always relevant now.
            // if (section == SettingsSection.General) return@forEach // Removed this check

            item {
                Text(section.getLabel(strings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
    val strings = LocalAppStrings.current

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

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (section) {
            SettingsSection.General -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        // Language Setting
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
                            }
                        )

                        if (platform.type == PlatformType.Android) {
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text(strings.enableStreamingNotificationLabel) },
                                trailingContent = {
                                    Switch(
                                        checked = state.enableStreamingNotification,
                                        onCheckedChange = { viewModel.setEnableStreamingNotification(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setEnableStreamingNotification(!state.enableStreamingNotification) }
                            )
                        }

                        if (platform.type == PlatformType.Desktop) {
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text(strings.autoStartLabel) },
                                supportingContent = { Text(strings.autoStartDesc) },
                                trailingContent = {
                                    Switch(
                                        checked = state.autoStart,
                                        onCheckedChange = { viewModel.setAutoStart(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setAutoStart(!state.autoStart) }
                            )
                            HorizontalDivider()
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
                                modifier = Modifier.clickable { /* Handled by dropdown */ }
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("袖珍模式") },
                                supportingContent = { Text("使用紧凑的窗口布局") },
                                trailingContent = {
                                    Switch(
                                        checked = state.pocketMode,
                                        onCheckedChange = { viewModel.setPocketMode(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setPocketMode(!state.pocketMode) }
                            )
                        }
                    }
                }
            }
            SettingsSection.Appearance -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                     Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                         
                         HorizontalDivider()

                         if (platform.type == PlatformType.Android) {
                             ListItem(
                                 headlineContent = { Text(strings.useDynamicColorLabel) },
                                 trailingContent = {
                                     Switch(
                                         checked = state.useDynamicColor,
                                         onCheckedChange = { viewModel.setUseDynamicColor(it) }
                                     )
                                 },
                                 modifier = Modifier.clickable { viewModel.setUseDynamicColor(!state.useDynamicColor) }
                             )
                             HorizontalDivider()
                         }

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
            }
            SettingsSection.Audio -> {
                if (platform.type == PlatformType.Android) {
                    // Android 音频参数
                    Column {
                        ListItem(
                            headlineContent = { Text(strings.autoConfigLabel) },
                            supportingContent = { Text(strings.autoConfigDesc) },
                            trailingContent = {
                                Switch(
                                        checked = state.isAutoConfig,
                                        onCheckedChange = { viewModel.setAutoConfig(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.setAutoConfig(!state.isAutoConfig) }
                        )
                        HorizontalDivider()
                        
                        // Manual settings (disabled if auto config is on)
                        val manualSettingsAlpha = if (state.isAutoConfig) 0.5f else 1f
                        val manualSettingsEnabled = !state.isAutoConfig
                        
                        Column(modifier = Modifier.alpha(manualSettingsAlpha)) {
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
                                }
                            )
                            HorizontalDivider()
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
                                }
                            )
                            HorizontalDivider()
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
                                }
                            )
                        }
                        HorizontalDivider()
                        // Android System Audio Processing (Combined NS + AGC)
                        ListItem(
                            headlineContent = { Text(strings.androidAudioProcessingLabel) },
                            supportingContent = { Text(strings.androidAudioProcessingDesc) },
                            trailingContent = {
                                Switch(
                                    checked = state.enableNS || state.enableAGC,
                                    onCheckedChange = { viewModel.setAndroidAudioProcessing(it) }
                                )
                            },
                            modifier = Modifier.clickable { viewModel.setAndroidAudioProcessing(!(state.enableNS || state.enableAGC)) }
                        )
                    }
                } else {
                    // Desktop Audio Processing
                    Column(modifier = Modifier.padding(8.dp)) {
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
                        // Noise Suppression
                        ListItem(
                            headlineContent = { Text(strings.enableNsLabel) },
                            trailingContent = { Switch(checked = state.enableNS, onCheckedChange = { viewModel.setEnableNS(it) }) },
                            modifier = Modifier.clickable { viewModel.setEnableNS(!state.enableNS) }
                        )
                        if (state.enableNS) {
                            var showNsTypeHelp by remember { mutableStateOf(false) }

                            ListItem(
                                headlineContent = { Text(strings.nsTypeLabel) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 帮助按钮
                                        IconButton(onClick = { showNsTypeHelp = true }) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = "降噪算法说明",
                                            )
                                        }

                                        // 算法选择下拉菜单
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
                                }
                            )

                            // 降噪算法帮助 Popup
                            if (showNsTypeHelp) {
                                NoiseReductionHelpPopup(onDismiss = { showNsTypeHelp = false })
                            }
                        }
                        
                        HorizontalDivider()

                        // AGC
                        ListItem(
                            headlineContent = { Text(strings.enableAgcLabel) },
                            trailingContent = { Switch(checked = state.enableAGC, onCheckedChange = { viewModel.setEnableAGC(it) }) },
                            modifier = Modifier.clickable { viewModel.setEnableAGC(!state.enableAGC) }
                        )
                        if (state.enableAGC) {
                             Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                 Text("${strings.agcTargetLabel}: ${state.agcTargetLevel}", style = MaterialTheme.typography.bodySmall)
                                 Slider(
                                     value = state.agcTargetLevel.toFloat(),
                                     onValueChange = { viewModel.setAgcTargetLevel(it.toInt()) },
                                     valueRange = 0f..100f
                                 )
                             }
                         }
                        
                        HorizontalDivider()

                        // VAD
                        ListItem(
                            headlineContent = { Text(strings.enableVadLabel) },
                            trailingContent = { Switch(checked = state.enableVAD, onCheckedChange = { viewModel.setEnableVAD(it) }) },
                            modifier = Modifier.clickable { viewModel.setEnableVAD(!state.enableVAD) }
                        )
                        if (state.enableVAD) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("${strings.vadThresholdLabel}: ${state.vadThreshold}", style = MaterialTheme.typography.bodySmall)
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
                            headlineContent = { Text(strings.enableDereverbLabel) },
                            trailingContent = { Switch(checked = state.enableDereverb, onCheckedChange = { viewModel.setEnableDereverb(it) }) },
                            modifier = Modifier.clickable { viewModel.setEnableDereverb(!state.enableDereverb) }
                        )
                        if (state.enableDereverb) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("${strings.dereverbLevelLabel}: ${((state.dereverbLevel * 100).toInt()) / 100f}", style = MaterialTheme.typography.bodySmall)
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
                            headlineContent = { Text(strings.amplificationLabel) },
                            supportingContent = {
                                Column {
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
                                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
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
                        )
                    }
                }
            }
            SettingsSection.About -> {
                val uriHandler = LocalUriHandler.current
                var showLicenseDialog by remember { mutableStateOf(false) }

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
                
                Column {
                    ListItem(
                        headlineContent = { Text(strings.developerLabel) },
                        supportingContent = { Text("LanRhyme、ChinsaaWei") },
                        leadingContent = { Icon(Icons.Default.Person, null) }
                    )
                    HorizontalDivider()
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
                        leadingContent = { Icon(Icons.Default.Code, null) }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(strings.contributorsLabel) },
                        supportingContent = { Text(strings.contributorsDesc) },
                        leadingContent = { Icon(Icons.Default.Group, null) },
                        modifier = Modifier.clickable { uriHandler.openUri("https://github.com/LanRhyme/MicYou/graphs/contributors") }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(strings.versionLabel) },
                        supportingContent = { Text(getAppVersion()) },
                        leadingContent = { Icon(Icons.Default.Info, null) },
                        trailingContent = {
                            TextButton(onClick = { viewModel.checkUpdateManual() }) {
                                Text(strings.checkUpdate)
                            }
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(strings.openSourceLicense) },
                        supportingContent = { Text(strings.viewLibraries) },
                        leadingContent = { Icon(Icons.Default.Description, null) },
                        modifier = Modifier.clickable { showLicenseDialog = true }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(strings.exportLog) },
                        supportingContent = { Text(strings.exportLogDesc) },
                        leadingContent = { Icon(Icons.Default.BugReport, null) },
                        modifier = Modifier.clickable {
                            viewModel.exportLog { path ->
                                if (path != null) {
                                    viewModel.showSnackbar("${strings.logExported}: $path")
                                }
                            }
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
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

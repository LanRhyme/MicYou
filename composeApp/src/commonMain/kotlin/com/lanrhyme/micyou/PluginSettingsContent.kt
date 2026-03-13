package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.plugin.PluginInfo
import com.lanrhyme.micyou.plugin.PluginPlatform

@Composable
fun PluginSettingsContent(
    viewModel: MainViewModel,
    cardOpacity: Float = 1f
) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalAppStrings.current
    val platform = getPlatform()
    
    var showDeleteDialog by remember { mutableStateOf<PluginInfo?>(null) }
    var showPlatformWarning by remember { mutableStateOf<PluginInfo?>(null) }
    var activePluginWindow by remember { mutableStateOf<String?>(null) }
    var activePluginSettings by remember { mutableStateOf<String?>(null) }
    
    val isMobile = platform.type.name == "Android"
    
    // 显示插件窗口
    activePluginWindow?.let { pluginId ->
        OpenPluginWindow(
            pluginId = pluginId,
            viewModel = viewModel,
            onClose = { activePluginWindow = null }
        )
    }
    
    // 显示插件设置
    activePluginSettings?.let { pluginId ->
        OpenPluginSettings(
            pluginId = pluginId,
            viewModel = viewModel,
            onClose = { activePluginSettings = null }
        )
    }
    
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val filePath = openPluginFileChooser()
                    filePath?.let { path ->
                        viewModel.importPlugin(path) { result ->
                            result.onSuccess {
                                viewModel.showSnackbar(strings.pluginImportSuccess)
                            }.onFailure { error ->
                                viewModel.showSnackbar(strings.pluginImportFailed.format(error.message ?: "Unknown error"))
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(strings.importPlugin)
            }
        }
        
        if (state.plugins.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f)
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        strings.noPluginsInstalled,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.plugins.forEach { pluginInfo ->
                    val uiProvider = viewModel.getPluginUIProvider(pluginInfo.manifest.id) as? com.lanrhyme.micyou.plugin.PluginUIProvider
                    val settingsProvider = viewModel.getPluginSettingsProvider(pluginInfo.manifest.id)
                    PluginItem(
                        pluginInfo = pluginInfo,
                        currentPlatform = platform.type,
                        onToggleEnabled = {
                            if (pluginInfo.isEnabled) {
                                viewModel.disablePlugin(pluginInfo.manifest.id)
                            } else {
                                val targetPlatform = pluginInfo.manifest.platform
                                val compatible = when (targetPlatform) {
                                    PluginPlatform.MOBILE -> isMobile
                                    PluginPlatform.DESKTOP -> !isMobile
                                    PluginPlatform.BOTH -> true
                                }
                                
                                if (!compatible) {
                                    showPlatformWarning = pluginInfo
                                } else {
                                    viewModel.enablePlugin(pluginInfo.manifest.id)
                                }
                            }
                        },
                        onDelete = { showDeleteDialog = pluginInfo },
                        onOpenWindow = if (uiProvider?.hasMainWindow == true) {
                            { activePluginWindow = pluginInfo.manifest.id }
                        } else null,
                        onOpenSettings = if (settingsProvider != null) {
                            { activePluginSettings = pluginInfo.manifest.id }
                        } else null,
                        strings = strings,
                        cardOpacity = cardOpacity
                    )
                }
            }
        }
    }
    
    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(strings.deletePlugin) },
            text = { Text(strings.deletePluginConfirm.format(showDeleteDialog!!.manifest.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePlugin(showDeleteDialog!!.manifest.id)
                        showDeleteDialog = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
    
    if (showPlatformWarning != null) {
        AlertDialog(
            onDismissRequest = { showPlatformWarning = null },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text(strings.pluginPlatformWarningTitle) },
            text = { 
                Text(strings.pluginPlatformWarning.format(
                    showPlatformWarning!!.manifest.name,
                    when (showPlatformWarning!!.manifest.platform) {
                        PluginPlatform.MOBILE -> "Mobile"
                        PluginPlatform.DESKTOP -> "Desktop"
                        PluginPlatform.BOTH -> "Both"
                    }
                ))
            },
            confirmButton = {
                TextButton(onClick = { showPlatformWarning = null }) {
                    Text(strings.ok)
                }
            }
        )
    }
}

@Composable
private fun PluginItem(
    pluginInfo: PluginInfo,
    currentPlatform: PlatformType,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    onOpenWindow: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    strings: AppStrings,
    cardOpacity: Float = 1f
) {
    val isMobile = currentPlatform.name == "Android"
    val isCompatible = when (pluginInfo.manifest.platform) {
        PluginPlatform.MOBILE -> isMobile
        PluginPlatform.DESKTOP -> !isMobile
        PluginPlatform.BOTH -> true
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.5f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pluginInfo.manifest.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (!isCompatible) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = "Incompatible",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Text(
                        pluginInfo.manifest.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 打开窗口按钮（仅当插件有窗口且已启用时显示）
                    if (onOpenWindow != null && pluginInfo.isEnabled && pluginInfo.isLoaded) {
                        TextButton(
                            onClick = onOpenWindow,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("打开窗口", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    
                    Switch(
                        checked = pluginInfo.isEnabled,
                        onCheckedChange = { onToggleEnabled() },
                        enabled = isCompatible && pluginInfo.isLoaded || !pluginInfo.isEnabled
                    )
                }
            }
            
            Spacer(Modifier.height(4.dp))
            
            Text(
                pluginInfo.manifest.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "v${pluginInfo.manifest.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (pluginInfo.manifest.tags.isNotEmpty()) {
                    pluginInfo.manifest.tags.take(3).forEach { tag ->
                        TagSurface(tag)
                    }
                }
                
                Spacer(Modifier.weight(1f))
                
                // 配置按钮（仅当插件有设置且已启用时显示）
                if (onOpenSettings != null && pluginInfo.isEnabled && pluginInfo.isLoaded) {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("配置", style = MaterialTheme.typography.labelMedium)
                    }
                }
                
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = strings.delete,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
expect fun OpenPluginWindow(
    pluginId: String,
    viewModel: MainViewModel,
    onClose: () -> Unit
)

@Composable
expect fun OpenPluginSettings(
    pluginId: String,
    viewModel: MainViewModel,
    onClose: () -> Unit
)

@Composable
private fun TagSurface(tag: String) {
    Card(
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Text(
            tag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

package com.lanrhyme.micyou

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lanrhyme.micyou.plugin.PluginSettingsProvider
import com.lanrhyme.micyou.plugin.PluginUIProvider
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.close
import micyou.composeapp.generated.resources.pluginSettingsTitle
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun OpenPluginWindow(
    pluginId: String,
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val uiProvider = viewModel.getPluginUIProvider(pluginId) as? PluginUIProvider
    
    if (uiProvider == null || !uiProvider.hasMainWindow) {
        onClose()
        return
    }
    
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        uiProvider.windowTitle,
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                
                Divider()
                
                // 插件窗口内容
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    uiProvider.MainWindow(onClose = onClose)
                }
            }
        }
    }
}

@Composable
actual fun OpenPluginSettings(
    pluginId: String,
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val settingsProvider = viewModel.getPluginSettingsProvider(pluginId) as? PluginSettingsProvider
    
    if (settingsProvider == null) {
        onClose()
        return
    }
    
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(Res.string.pluginSettingsTitle),
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                
                Divider()
                
                // 设置内容
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    settingsProvider.SettingsContent()
                }
                
                // 底部按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onClose) {
                        Text(stringResource(Res.string.close))
                    }
                }
            }
        }
    }
}

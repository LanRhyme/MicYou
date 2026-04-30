package com.lanrhyme.micyou

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import com.lanrhyme.micyou.plugin.PluginSettingsProvider
import com.lanrhyme.micyou.plugin.PluginUIProvider
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.close
import micyou.composeapp.generated.resources.done
import micyou.composeapp.generated.resources.pluginConfiguration
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
    
    Window(
        onCloseRequest = onClose,
        title = uiProvider.windowTitle,
        state = rememberWindowState(
            size = DpSize(uiProvider.windowWidth, uiProvider.windowHeight)
        ),
        resizable = uiProvider.windowResizable
    ) {
        uiProvider.MainWindow(onClose = onClose)
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
    
    DialogWindow(
        onCloseRequest = onClose,
        title = stringResource(Res.string.pluginSettingsTitle),
        state = rememberDialogState(size = DpSize(500.dp, 600.dp)),
        resizable = true
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(Res.string.pluginConfiguration),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // 设置内容
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    settingsProvider.SettingsContent()
                }
                
                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onClose) {
                        Text(stringResource(Res.string.done))
                    }
                }
            }
        }
    }
}

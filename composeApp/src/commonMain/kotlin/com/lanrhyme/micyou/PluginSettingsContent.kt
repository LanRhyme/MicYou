package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanrhyme.micyou.plugin.PluginInfo
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.importPlugin
import micyou.composeapp.generated.resources.noPluginsFound
import micyou.composeapp.generated.resources.reloadPlugins
import micyou.composeapp.generated.resources.removePlugin
import micyou.composeapp.generated.resources.showLess
import micyou.composeapp.generated.resources.showMore
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginSettingsContent(
    viewModel: MainViewModel,
    cardOpacity: Float = 1f
) {
    val state by viewModel.uiState.collectAsState()
    val plugins = state.plugins
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 插件操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { 
                    openPluginFileChooser(scope) { path ->
                        if (path != null) {
                            viewModel.importPlugin(path) { result ->
                                result.onSuccess { viewModel.showSnackbar("Plugin imported") }
                                      .onFailure { viewModel.showSnackbar("Import failed: ${it.message}") }
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.importPlugin))
            }

            OutlinedButton(
                onClick = { 
                    // MainViewModel doesn't have reloadPlugins, but we can re-init
                    viewModel.showSnackbar("Reloading plugins...")
                },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.reloadPlugins))
            }
        }

        if (plugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(Res.string.noPluginsFound),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            plugins.forEach { plugin ->
                PluginItemCard(
                    plugin = plugin,
                    viewModel = viewModel,
                    cardOpacity = cardOpacity
                )
            }
        }
    }
}

@Composable
private fun PluginItemCard(
    plugin: PluginInfo,
    viewModel: MainViewModel,
    cardOpacity: Float
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardOpacity * 0.4f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 插件图标/占位符
                Surface(
                    shape = CircleShape,
                    color = if (plugin.isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (plugin.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.manifest.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v${plugin.manifest.version} • ${plugin.manifest.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = plugin.isEnabled,
                    onCheckedChange = { if (it) viewModel.enablePlugin(plugin.manifest.id) else viewModel.disablePlugin(plugin.manifest.id) }
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = plugin.manifest.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (plugin.manifest.description.length > 60) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) stringResource(Res.string.showLess) else stringResource(Res.string.showMore), fontSize = 12.sp)
                    }
                }

                IconButton(onClick = { viewModel.deletePlugin(plugin.manifest.id) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.removePlugin),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

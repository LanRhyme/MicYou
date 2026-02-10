package com.lanrhyme.androidmic_md

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val viewModel = viewModel { MainViewModel() }
        val state by viewModel.uiState.collectAsState()
        val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
        val platform = remember { getPlatform() }
        val isClient = platform.type == PlatformType.Android

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("AndroidMic") },
                    actions = {
                        IconButton(onClick = { /* TODO: 设置 */ }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 连接模式
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("连接模式", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.mode == ConnectionMode.Wifi,
                                onClick = { viewModel.setMode(ConnectionMode.Wifi) },
                                label = { Text("Wi-Fi") }
                            )
                            FilterChip(
                                selected = state.mode == ConnectionMode.Usb,
                                onClick = { viewModel.setMode(ConnectionMode.Usb) },
                                label = { Text("USB") }
                            )
                        }

                        if (state.mode == ConnectionMode.Wifi) {
                            if (isClient) {
                                OutlinedTextField(
                                    value = state.ipAddress,
                                    onValueChange = { viewModel.setIp(it) },
                                    label = { Text("目标 IP 地址") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = state.port,
                                    onValueChange = { viewModel.setPort(it) },
                                    label = { Text("端口") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text("监听端口: ${state.port}")
                                // TODO: 显示本地 IP
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 可视化
                if (state.streamState == StreamState.Streaming) {
                    Text("音频电平")
                    LinearProgressIndicator(
                        progress = { audioLevel },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 大启动按钮
                val isStreaming = state.streamState == StreamState.Streaming
                FilledTonalButton(
                    onClick = { viewModel.toggleStream() },
                    modifier = Modifier.size(120.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isStreaming) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isStreaming) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (isStreaming) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(if (isStreaming) "停止" else "开始")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 状态
                Text(
                    text = "状态: ${state.streamState}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

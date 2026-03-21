package com.example.sampleplugin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.plugin.*

class SamplePlugin : Plugin, PluginUIProvider, PluginSettingsProvider {

    private var context: PluginContext? = null
    private var counter: Int = 0

    override val manifest = PluginManifest(
        id = "com.example.sample-plugin",
        name = "Sample Plugin",
        version = "1.0.0",
        author = "MicYou Team",
        description = "A sample plugin demonstrating the MicYou Plugin API.",
        tags = listOf("demo"),
        platform = PluginPlatform.BOTH,
        minApiVersion = "1.0.0",
        mainClass = "com.example.sampleplugin.SamplePlugin"
    )

    override val hasMainWindow: Boolean = true
    override val hasDialog: Boolean = true
    override val mobileUIMode: MobileUIMode = MobileUIMode.NewScreen

    override fun onLoad(context: PluginContext) {
        this.context = context
        counter = context.getInt("counter", 0)
        context.log("SamplePlugin loaded")
    }

    override fun onEnable() {
        context?.log("SamplePlugin enabled")
    }

    override fun onDisable() {
        context?.log("SamplePlugin disabled")
    }

    override fun onUnload() {
        context?.log("SamplePlugin unloaded")
        context = null
    }

    @Composable
    override fun MainWindow(onClose: () -> Unit) {
        var localCounter by remember { mutableStateOf(counter) }
        val host = context?.host
        val streamState by host?.streamState?.collectAsState() ?: remember { mutableStateOf(StreamState.Idle) }
        val audioLevel by host?.audioLevels?.collectAsState() ?: remember { mutableStateOf(0f) }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.headlineMedium
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Counter", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = localCounter.toString(),
                            style = MaterialTheme.typography.displayLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            counter++
                            context?.putInt("counter", counter)
                            localCounter = counter
                        }) {
                            Text("Increment")
                        }
                    }
                }

                host?.let { h ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Host Status", style = MaterialTheme.typography.titleMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Stream State:")
                                Text(streamState.name)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Audio Level:")
                                Text("%.2f".format(audioLevel))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { h.showSnackbar("Hello from ${manifest.name}!") }) {
                                    Text("Snackbar")
                                }
                                Button(onClick = { h.showNotification(manifest.name, "Notification from plugin!") }) {
                                    Text("Notify")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(onClick = onClose) {
                    Text("Close")
                }
            }
        }
    }

    @Composable
    override fun DialogContent(onDismiss: () -> Unit) {
        var localCounter by remember { mutableStateOf(counter) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(manifest.name) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Counter: $localCounter")
                    Button(onClick = {
                        counter++
                        context?.putInt("counter", counter)
                        localCounter = counter
                    }) {
                        Text("Increment")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        )
    }

    @Composable
    override fun SettingsContent() {
        var enableFeature by remember { mutableStateOf(context?.getBoolean("enableFeature", false) ?: false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Feature")
                    Switch(
                        checked = enableFeature,
                        onCheckedChange = {
                            enableFeature = it
                            context?.putBoolean("enableFeature", it)
                        }
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Plugin Info", style = MaterialTheme.typography.titleMedium)
                    Text("Version: ${manifest.version}")
                    Text("Author: ${manifest.author}")
                    Text("Platform: ${manifest.platform.name}")
                }
            }
        }
    }
}

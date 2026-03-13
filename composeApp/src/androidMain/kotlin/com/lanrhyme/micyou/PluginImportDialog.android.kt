package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
actual fun PluginImportDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (filePath: String) -> Unit
) {
    val strings = LocalAppStrings.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.pluginImportTitle) },
        text = {
            Column {
                Text("Plugins are not supported on Android platform.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.ok)
            }
        }
    )
}

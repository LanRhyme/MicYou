package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginImportDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (filePath: String) -> Unit
) {    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.pluginImportTitle)) },
        text = {
            Column {
                Text("Plugins are not supported on Android platform.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.ok))
            }
        }
    )
}

package com.lanrhyme.micyou.ui.dialog
import com.lanrhyme.micyou.R

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lanrhyme.micyou.ui.dialog.PluginImportDialog

@Composable
fun PluginImportDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (filePath: String) -> Unit
) {    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pluginImportTitle)) },
        text = {
            Column {
                Text(stringResource(R.string.pluginNotSupportedAndroid))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}
package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val filePickerScope = CoroutineScope(Dispatchers.Main)

actual fun openPluginFileChooser(onResult: (String?) -> Unit) {
    filePickerScope.launch {
        try {
            val file = FileKit.openFilePicker(
                type = FileKitType.File(extensions = listOf("zip", "jar"))
            )
            onResult(file?.absolutePath())
        } catch (e: Exception) {
            Logger.e("PluginFileChooser", "Failed to pick plugin file", e)
            onResult(null)
        }
    }
}
package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

actual fun openPluginFileChooser(scope: CoroutineScope, onResult: (String?) -> Unit) {
    scope.launch {
        try {
            val file = FileKit.openFilePicker(
                type = FileKitType.File(extensions = listOf("zip", "jar"))
            )
            val savedPath = file?.let { copyPluginToInternalStorage(it) }
            onResult(savedPath)
        } catch (e: Exception) {
            Logger.e("PluginFileChooser", "Failed to pick plugin file", e)
            onResult(null)
        }
    }
}

private suspend fun copyPluginToInternalStorage(file: PlatformFile): String? {
    return try {
        val context = AndroidContext.getContext() ?: return null

        val pluginDir = File(context.cacheDir, "plugin_imports")
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
        }

        val fileName = file.name
        val outputFile = File(pluginDir, fileName)
        outputFile.writeBytes(file.readBytes())

        outputFile.absolutePath
    } catch (e: Exception) {
        Logger.e("PluginFileChooser", "Failed to copy plugin file", e)
        null
    }
}
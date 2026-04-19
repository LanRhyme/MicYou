package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// 文件选择是短暂的用户交互操作，使用 GlobalScope 是可接受的
// 协程会在用户完成选择（或取消）后立即结束
actual fun openPluginFileChooser(onResult: (String?) -> Unit) {
    GlobalScope.launch(Dispatchers.Main) {
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
        outputFile.writeBytes(bytes)

        outputFile.absolutePath
    } catch (e: Exception) {
        Logger.e("PluginFileChooser", "Failed to copy plugin file", e)
        null
    }
}
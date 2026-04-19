package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

actual object BackgroundImagePicker {
    // 文件选择是短暂的用户交互操作，使用 GlobalScope 是可接受的
    // 协程会在用户完成选择（或取消）后立即结束
    actual fun pickImage(onResult: (String?) -> Unit) {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val file = FileKit.openFilePicker(type = FileKitType.Image)
                val savedPath = file?.let { copyToInternalStorage(it) }
                onResult(savedPath)
            } catch (e: Exception) {
                Logger.e("BackgroundImagePicker", "Failed to pick image", e)
                onResult(null)
            }
        }
    }

    private suspend fun copyToInternalStorage(file: PlatformFile): String? {
        return try {
            val context = AndroidContext.getContext() ?: return null
            val bytes = file.readBytes()

            val backgroundDir = File(context.filesDir, "backgrounds")
            if (!backgroundDir.exists()) {
                backgroundDir.mkdirs()
            }

            val extension = file.extension
            val fileName = "custom_background.$extension"
            val outputFile = File(backgroundDir, fileName)
            outputFile.writeBytes(bytes)

            outputFile.absolutePath
        } catch (e: Exception) {
            Logger.e("BackgroundImagePicker", "Failed to copy image to internal storage", e)
            null
        }
    }
}
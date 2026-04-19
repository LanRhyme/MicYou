package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

actual object BackgroundImagePicker {
    actual fun pickImage(onResult: (String?) -> Unit) {
        runBlocking(Dispatchers.IO) {
            try {
                val file = FileKit.openFilePicker(
                type = FileKitType.File(extensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff", "tif"))
            )
                onResult(file?.absolutePath())
            } catch (e: Exception) {
                Logger.e("BackgroundImagePicker", "Failed to pick image", e)
                onResult(null)
            }
        }
    }
}
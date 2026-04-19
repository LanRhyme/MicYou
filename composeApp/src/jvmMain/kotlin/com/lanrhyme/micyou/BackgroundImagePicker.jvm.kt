package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

actual object BackgroundImagePicker {
    actual fun pickImage(scope: CoroutineScope, onResult: (String?) -> Unit) {
        scope.launch {
            try {
                val file = FileKit.openFilePicker(
                    type = FileKitType.File(
                        extensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                    )
                )
                onResult(file?.absolutePath())
            } catch (e: Exception) {
                Logger.e("BackgroundImagePicker", "Failed to pick image", e)
                onResult(null)
            }
        }
    }
}
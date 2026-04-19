package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

actual object BackgroundImagePicker {
    // 文件选择是短暂的用户交互操作，使用 GlobalScope 是可接受的
    // 协程会在用户完成选择（或取消）后立即结束
    actual fun pickImage(onResult: (String?) -> Unit) {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val file = FileKit.openFilePicker(
                    type = FileKitType.File(
                        extensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff", "tif")
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
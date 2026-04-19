package com.lanrhyme.micyou

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 文件选择是短暂的用户交互操作，使用 GlobalScope 是可接受的
// 协程会在用户完成选择（或取消）后立即结束
actual fun openPluginFileChooser(onResult: (String?) -> Unit) {
    GlobalScope.launch(Dispatchers.Main) {
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
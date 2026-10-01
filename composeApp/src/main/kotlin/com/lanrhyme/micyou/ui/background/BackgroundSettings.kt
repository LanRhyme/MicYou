/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.lanrhyme.micyou.ui.background

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import com.lanrhyme.micyou.ui.background.BackgroundImagePicker
import com.lanrhyme.micyou.ui.background.BackgroundSettings
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger

data class BackgroundSettings(
    val imagePath: String = "",
    val brightness: Float = 0.5f,
    val blurRadius: Float = 0f,
    val cardOpacity: Float = 1f,
    val enableHazeEffect: Boolean = false
) {
    val hasCustomBackground: Boolean
        get() = imagePath.isNotEmpty()
}

object BackgroundImagePicker {
    fun pickImage(scope: CoroutineScope, onResult: (String?) -> Unit) {
        scope.launch {
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
            val context = ContextHelper.getContext() ?: return null
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
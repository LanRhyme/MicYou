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

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import com.lanrhyme.micyou.ui.background.loadImageBitmap
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger

fun loadImageBitmap(path: String): ImageBitmap? {
    return try {
        val context = ContextHelper.getContext() ?: return null
        
        val inputStream = when {
            path.startsWith("/") -> File(path).inputStream()
            path.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(path))
            path.startsWith("file://") -> File(Uri.parse(path).path ?: return null).inputStream()
            else -> File(path).inputStream()
        } ?: return null
        
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        Logger.e("BackgroundImage", "Failed to load image: $path", e)
        null
    }
}
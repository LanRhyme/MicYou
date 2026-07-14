package com.lanrhyme.micyou.ui.background

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
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

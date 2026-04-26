package com.lanrhyme.micyou

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI

actual fun loadImageBitmap(path: String): ImageBitmap? {
    return try {
        val file = if (path.startsWith("file://")) {
            File(URI(path))
        } else {
            File(path)
        }
        
        if (!file.exists()) {
            Logger.w("BackgroundImage", "Image file not found: $path")
            return null
        }
    val bytes = file.readBytes()
    val image = Image.makeFromEncoded(bytes)
        image.toComposeImageBitmap()
    } catch (e: Exception) {
        Logger.e("BackgroundImage", "Failed to load image: $path", e)
        null
    }
}

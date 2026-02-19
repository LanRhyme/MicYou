package com.lanrhyme.micyou

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Image

@Composable
actual fun VideoPreview(frame: VideoFrameUi?, modifier: Modifier) {
    val bitmap: ImageBitmap? = remember(frame?.sequenceNumber) {
        frame?.let {
            runCatching { Image.makeFromEncoded(it.jpegBytes).toComposeImageBitmap() }.getOrNull()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Video preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("No video", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

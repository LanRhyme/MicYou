package com.lanrhyme.micyou

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
actual fun VideoPreview(frame: VideoFrameUi?, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("Video sender mode", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

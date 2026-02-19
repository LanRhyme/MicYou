package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun VideoPreview(frame: VideoFrameUi?, modifier: Modifier = Modifier)

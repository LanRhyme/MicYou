package com.lanrhyme.micyou

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

@Composable
fun rememberHazeState(): HazeState {
    return remember { HazeState() }
}

@Composable
fun CustomBackground(
    settings: BackgroundSettings,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    forcePureBlackBackground: Boolean = false
) {
    if (forcePureBlackBackground) {
        Box(
            modifier = modifier.background(Color.Black)
        )
        return
    }

    if (!settings.hasCustomBackground) {
        return
    }
    val imageBitmap = remember(settings.imagePath) {
        loadImageBitmap(settings.imagePath)
    }
    
    if (imageBitmap != null) {
        Box(
            modifier = modifier.then(
                if (hazeState != null && settings.enableHazeEffect) {
                    Modifier.hazeSource(state = hazeState)
                } else {
                    Modifier
                }
            )
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = settings.blurRadius.dp),
                contentScale = ContentScale.Crop
            )
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 1f - settings.brightness))
            )
        }
    }
}

@Composable
fun HazeCard(
    hazeState: HazeState?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    hazeColor: Color = Color.White.copy(alpha = 0.7f),
    content: @Composable () -> Unit
) {
    if (enabled && hazeState != null) {
        Box(
            modifier = modifier.hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = hazeColor,
                    tints = listOf(HazeTint(color = hazeColor))
                )
            )
        ) {
            content()
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

@Composable
fun HazeSurface(
    hazeState: HazeState?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = Color.Transparent,
    hazeColor: Color = Color.White.copy(alpha = 0.7f),
    content: @Composable () -> Unit
) {
    if (enabled && hazeState != null) {
        Box(
            modifier = modifier
                .clip(shape)
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = hazeColor,
                        tints = listOf(HazeTint(color = hazeColor))
                    )
                )
        ) {
            content()
        }
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(color)
        ) {
            content()
        }
    }
}

@Composable
fun CardWithOpacity(
    opacity: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.graphicsLayer { alpha = opacity }) {
        content()
    }
}

expect fun loadImageBitmap(path: String): ImageBitmap?

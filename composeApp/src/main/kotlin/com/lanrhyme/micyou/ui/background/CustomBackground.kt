/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
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
import com.lanrhyme.micyou.ui.compose.haze.HazeState
import com.lanrhyme.micyou.ui.compose.haze.HazeStyle
import com.lanrhyme.micyou.ui.compose.haze.HazeTint
import com.lanrhyme.micyou.ui.compose.haze.hazeEffect
import com.lanrhyme.micyou.ui.compose.haze.hazeSource
import com.lanrhyme.micyou.settings.Settings
import com.lanrhyme.micyou.ui.background.BackgroundSettings
import com.lanrhyme.micyou.ui.background.CustomBackground
import com.lanrhyme.micyou.ui.background.HazeSurface
import com.lanrhyme.micyou.ui.background.loadImageBitmap

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
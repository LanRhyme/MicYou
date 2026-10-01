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

package com.lanrhyme.micyou.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive (2025) 形状系统
 * 更大的圆角半径，更有表现力的视觉风格
 */

/**
 * Expressive形状 - 标准Expressive风格圆角
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // M3标准: 4dp
    small = RoundedCornerShape(12.dp),       // M3标准: 8dp
    medium = RoundedCornerShape(20.dp),      // M3标准: 12dp
    large = RoundedCornerShape(28.dp),       // M3标准: 16dp
    extraLarge = RoundedCornerShape(40.dp)   // M3标准: 28dp
)

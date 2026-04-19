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

/**
 * SuperExpressive形状 - 超级表达性风格，更大的圆角
 * 用于特殊组件或强调效果
 */
val SuperExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(40.dp),
    extraLarge = RoundedCornerShape(56.dp)
)

/**
 * SuperRounded形状 - 超圆角形状
 * 用于按钮、卡片等需要强调的组件
 */
val SuperRoundedShape = RoundedCornerShape(48.dp)

/**
 * ExtraRounded形状 - 超大圆角
 */
val ExtraRoundedShape = RoundedCornerShape(32.dp)

/**
 * Pill形状 - 药丸形状（完全圆角）
 */
val PillShape = RoundedCornerShape(percent = 50)
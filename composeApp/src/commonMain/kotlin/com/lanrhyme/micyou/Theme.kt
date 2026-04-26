// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
// Adapted for MicYou
package com.lanrhyme.micyou

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.lanrhyme.micyou.theme.ExpressiveShapes
import com.lanrhyme.micyou.theme.PaletteStyle
import com.lanrhyme.micyou.theme.animateAsState
import com.lanrhyme.micyou.theme.dynamicColorScheme
import kotlin.math.abs

// 导出类型供外部使用
typealias AppPaletteStyle = PaletteStyle

/**
 * 预设种子颜色 - 参考 InstallerX-Revived PresetColors
 */
object PresetColors {
    val Default = Color(0xFF4A672D)
    val Pink = Color(0xFFB94073)
    val Red = Color(0xFFBA1A1A)
    val Orange = Color(0xFF944A00)
    val Amber = Color(0xFF8C5300)
    val Yellow = Color(0xFF795900)
    val Lime = Color(0xFF5E6400)
    val Green = Color(0xFF006D39)
    val Cyan = Color(0xFF006A64)
    val Teal = Color(0xFF006874)
    val LightBlue = Color(0xFF00639B)
    val Blue = Color(0xFF335BBC)
    val Indigo = Color(0xFF5355A9)
    val Purple = Color(0xFF6750A4)
    val DeepPurple = Color(0xFF7E42A4)
    val BlueGrey = Color(0xFF575D7E)
    val Brown = Color(0xFF7D524A)
    val Grey = Color(0xFF5F6162)
    val allColors = listOf(Default, Pink, Red, Orange, Amber, Yellow, Lime, Green, Cyan, Teal, LightBlue, Blue, Indigo, Purple, DeepPurple, BlueGrey, Brown, Grey)
}

// HSV 工具函数 - 用于 ColorPicker
fun colorToHSV(color: Int, hsv: FloatArray) {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    var h = 0f
    var s = 0f
    if (max != min) {
        val delta = max - min
        s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
        h = when (max) {
            r -> ((g - b) / delta + if (g < b) 6f else 0f)
            g -> ((b - r) / delta + 2f)
            else -> ((r - g) / delta + 4f)
        } / 6f
    }
    hsv[0] = h * 360f
    hsv[1] = s
    hsv[2] = l
}

fun hsvToColor(hsv: FloatArray): Int {
    val h = hsv[0] / 360f
    val s = hsv[1]
    val l = hsv[2]
    if (s == 0f) {
        val v = (l * 255f).toInt()
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h * 6f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when ((h * 6f).toInt() % 6) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return (0xFF shl 24) or (((r + m) * 255f).toInt() shl 16) or (((g + m) * 255f).toInt() shl 8) or ((b + m) * 255f).toInt()
}

// 兼容旧名称
val MD3SeedColors = PresetColors

/**
 * OLED 纯黑背景调整 - 仅用于 OLED 模式
 */
private fun ColorScheme.withOledDarkBackground(): ColorScheme {
    val pureBlack = Color(0xFF000000)
    val lowSurface = Color(0xFF121212)
    val mediumSurface = Color(0xFF1E1E1E)
    val highSurface = Color(0xFF2A2A2A)
    val topSurface = Color(0xFF363636)
    return copy(
        background = pureBlack,
        surface = pureBlack,
        surfaceDim = pureBlack,
        surfaceBright = mediumSurface,
        surfaceContainerLowest = pureBlack,
        surfaceContainerLow = lowSurface,
        surfaceContainer = mediumSurface,
        surfaceContainerHigh = highSurface,
        surfaceContainerHighest = topSurface,
        surfaceVariant = highSurface,
        inverseSurface = Color(0xFFE6E6E6),
        scrim = pureBlack
    )
}

enum class ThemeMode { System, Light, Dark }

@Composable
fun isDarkThemeActive(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

// 默认值
val DefaultSeedColor = PresetColors.Default
val DefaultPaletteStyle = PaletteStyle.TonalSpot

/**
 * 应用主题 - 参考 InstallerX-Revived InstallerTheme.kt
 * 完全依赖 materialkolor 生成的配色方案，强制使用 Expressive (2025)
 */
@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.System,
    seedColor: Color = DefaultSeedColor,
    useDynamicColor: Boolean = false,
    oledPureBlack: Boolean = false,
    paletteStyle: PaletteStyle = DefaultPaletteStyle,
    useExpressiveShapes: Boolean = true,
    content: @Composable () -> Unit
) {
    val isDark = isDarkThemeActive(themeMode)

    // 动态颜色（如果启用）- 使用用户选择的 paletteStyle
    val dynamicScheme = if (useDynamicColor) getDynamicColorScheme(isDark, paletteStyle) else null

    // 使用 materialkolor 生成配色方案 - 不做任何手动调整
    val baseColorScheme = dynamicScheme ?: remember(seedColor, isDark, paletteStyle) {
        dynamicColorScheme(
            keyColor = seedColor,
            isDark = isDark,
            style = paletteStyle
        )
    }

    // 应用颜色动画
    val animatedColorScheme = baseColorScheme.animateAsState()

    // OLED 纯黑处理
    val finalColorScheme = if (isDark && oledPureBlack) animatedColorScheme.withOledDarkBackground() else animatedColorScheme

    MaterialTheme(
        colorScheme = finalColorScheme,
        shapes = if (useExpressiveShapes) ExpressiveShapes else MaterialTheme.shapes,
        content = content
    )
}
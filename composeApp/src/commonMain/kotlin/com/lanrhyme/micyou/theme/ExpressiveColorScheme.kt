package com.lanrhyme.micyou.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * Material 3 Expressive (2025) 颜色方案生成器
 * 支持多种调色板风格：Tonal, Expressive, Vibrant, Monochrome, Rainbow
 */

enum class PaletteStyle {
    Tonal,       // 经典M3色调风格 - 平衡饱和度
    Expressive,  // 表达性风格 - 高饱和度，更有表现力
    Vibrant,     // 鲜艳风格 - 最高饱和度，充满活力
    Monochrome,  // 单色风格 - 低饱和度，简约中性
    Rainbow      // 彩虹风格 - 色相偏移，多彩变化
}

object ExpressiveColorUtils {
    fun colorToHSL(color: Int): FloatArray {
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
        return floatArrayOf(h * 360f, s, l)
    }

    fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or (((r + m) * 255f).toInt() shl 16) or
                (((g + m) * 255f).toInt() shl 8) or ((b + m) * 255f).toInt()
    }

    /**
     * Expressive风格色调生成
     * 根据PaletteStyle调整色度(chroma)倍率
     */
    fun expressiveTone(
        seedColor: Color,
        tone: Int,
        paletteStyle: PaletteStyle = PaletteStyle.Tonal,
        colorRole: ColorRole = ColorRole.Primary
    ): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val chromaMultiplier = getChromaMultiplier(paletteStyle, colorRole)
        val adjustedChroma = (hsl[1] * chromaMultiplier).coerceIn(0f, 1f)
        return Color(hslToColor(hsl[0], adjustedChroma, tone / 100f))
    }

    /**
     * 中性色调生成 - Expressive风格下带微弱色彩倾向
     */
    fun neutralTone(
        seedColor: Color,
        tone: Int,
        paletteStyle: PaletteStyle = PaletteStyle.Tonal
    ): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val neutralChroma = when (paletteStyle) {
            PaletteStyle.Monochrome -> 0f
            PaletteStyle.Tonal -> 0.005f
            PaletteStyle.Expressive -> hsl[1] * 0.12f  // Expressive: 带主色调倾向
            PaletteStyle.Vibrant -> hsl[1] * 0.15f
            PaletteStyle.Rainbow -> hsl[1] * 0.08f
        }
        return Color(hslToColor(hsl[0], neutralChroma.coerceIn(0f, 0.1f), tone / 100f))
    }

    /**
     * 中性变体色调生成 - 用于surfaceVariant
     */
    fun neutralVariantTone(
        seedColor: Color,
        tone: Int,
        paletteStyle: PaletteStyle = PaletteStyle.Tonal
    ): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val variantChroma = when (paletteStyle) {
            PaletteStyle.Monochrome -> 0.01f
            PaletteStyle.Tonal -> 0.04f
            PaletteStyle.Expressive -> hsl[1] * 0.2f
            PaletteStyle.Vibrant -> hsl[1] * 0.25f
            PaletteStyle.Rainbow -> hsl[1] * 0.15f
        }
        return Color(hslToColor(hsl[0], variantChroma.coerceIn(0f, 0.2f), tone / 100f))
    }

    /**
     * 第三颜色生成 - 根据PaletteStyle进行色相偏移
     */
    fun tertiaryColor(seedColor: Color, paletteStyle: PaletteStyle = PaletteStyle.Tonal): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val hueOffset = when (paletteStyle) {
            PaletteStyle.Tonal -> 60f
            PaletteStyle.Expressive -> 90f
            PaletteStyle.Vibrant -> 120f
            PaletteStyle.Monochrome -> 30f
            PaletteStyle.Rainbow -> 180f
        }
        val newHue = (hsl[0] + hueOffset) % 360f
        return Color(hslToColor(newHue, hsl[1], hsl[2]))
    }

    private fun getChromaMultiplier(paletteStyle: PaletteStyle, colorRole: ColorRole): Float {
        return when (paletteStyle) {
            PaletteStyle.Tonal -> when (colorRole) {
                ColorRole.Primary -> 1.0f
                ColorRole.Secondary -> 0.5f
                ColorRole.Tertiary -> 0.6f
            }
            PaletteStyle.Expressive -> when (colorRole) {
                ColorRole.Primary -> 1.35f
                ColorRole.Secondary -> 1.0f
                ColorRole.Tertiary -> 1.25f
            }
            PaletteStyle.Vibrant -> when (colorRole) {
                ColorRole.Primary -> 1.5f
                ColorRole.Secondary -> 1.3f
                ColorRole.Tertiary -> 1.4f
            }
            PaletteStyle.Monochrome -> when (colorRole) {
                ColorRole.Primary -> 0.4f
                ColorRole.Secondary -> 0.3f
                ColorRole.Tertiary -> 0.35f
            }
            PaletteStyle.Rainbow -> when (colorRole) {
                ColorRole.Primary -> 1.2f
                ColorRole.Secondary -> 1.0f
                ColorRole.Tertiary -> 1.3f
            }
        }
    }
}

enum class ColorRole {
    Primary, Secondary, Tertiary
}

/**
 * 生成Expressive配色方案
 */
fun generateExpressiveColorScheme(
    seedColor: Color,
    isDark: Boolean,
    paletteStyle: PaletteStyle = PaletteStyle.Tonal
): androidx.compose.material3.ColorScheme {
    return if (isDark) {
        generateExpressiveDarkColorScheme(seedColor, paletteStyle)
    } else {
        generateExpressiveLightColorScheme(seedColor, paletteStyle)
    }
}

/**
 * Material 3 Expressive 浅色配色方案
 * 关键特征：背景较暗，表面容器更亮，卡片浮起效果
 */
private fun generateExpressiveLightColorScheme(seed: Color, style: PaletteStyle): androidx.compose.material3.ColorScheme {
    val tertiary = ExpressiveColorUtils.tertiaryColor(seed, style)

    return lightColorScheme(
        // Primary - 主要操作颜色，Expressive风格更鲜艳
        primary = ExpressiveColorUtils.expressiveTone(seed, 35, style, ColorRole.Primary),
        onPrimary = Color.White,
        primaryContainer = ExpressiveColorUtils.expressiveTone(seed, 85, style, ColorRole.Primary),
        onPrimaryContainer = ExpressiveColorUtils.expressiveTone(seed, 10, style, ColorRole.Primary),

        // Secondary - 辅助颜色
        secondary = ExpressiveColorUtils.expressiveTone(seed, 40, style, ColorRole.Secondary),
        onSecondary = Color.White,
        secondaryContainer = ExpressiveColorUtils.expressiveTone(seed, 90, style, ColorRole.Secondary),
        onSecondaryContainer = ExpressiveColorUtils.expressiveTone(seed, 10, style, ColorRole.Secondary),

        // Tertiary - 第三颜色，色相偏移
        tertiary = ExpressiveColorUtils.expressiveTone(tertiary, 40, style, ColorRole.Tertiary),
        onTertiary = Color.White,
        tertiaryContainer = ExpressiveColorUtils.expressiveTone(tertiary, 90, style, ColorRole.Tertiary),
        onTertiaryContainer = ExpressiveColorUtils.expressiveTone(tertiary, 10, style, ColorRole.Tertiary),

        // Error - 错误颜色
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),

        // Background - Expressive关键：背景最暗(tone 90)，让卡片浮起
        background = ExpressiveColorUtils.neutralTone(seed, 90, style),
        onBackground = ExpressiveColorUtils.neutralTone(seed, 10, style),

        // Surface - 基础表面，比背景稍亮
        surface = ExpressiveColorUtils.neutralTone(seed, 95, style),
        onSurface = ExpressiveColorUtils.neutralTone(seed, 10, style),

        // Surface层级 - Expressive规范：浅色模式下容器比背景更亮
        // background=90，容器层级递增更亮，体现卡片浮起效果
        surfaceDim = ExpressiveColorUtils.neutralTone(seed, 85, style),
        surfaceBright = ExpressiveColorUtils.neutralTone(seed, 98, style),

        // Surface Container层级 - 关键：所有容器都比background(90)更亮
        // Lowest=100(纯白)，Highest=96(仍比background亮)
        surfaceContainerLowest = ExpressiveColorUtils.neutralTone(seed, 100, style),
        surfaceContainerLow = ExpressiveColorUtils.neutralTone(seed, 98, style),
        surfaceContainer = ExpressiveColorUtils.neutralTone(seed, 96, style),
        surfaceContainerHigh = ExpressiveColorUtils.neutralTone(seed, 94, style),
        surfaceContainerHighest = ExpressiveColorUtils.neutralTone(seed, 92, style),

        // Surface Variant - 带主色调倾向的表面变体
        surfaceVariant = ExpressiveColorUtils.neutralVariantTone(seed, 90, style),
        onSurfaceVariant = ExpressiveColorUtils.neutralVariantTone(seed, 30, style),

        // Outline
        outline = ExpressiveColorUtils.neutralVariantTone(seed, 50, style),
        outlineVariant = ExpressiveColorUtils.neutralVariantTone(seed, 80, style),

        // Inverse
        inverseSurface = ExpressiveColorUtils.neutralTone(seed, 20, style),
        inverseOnSurface = ExpressiveColorUtils.neutralTone(seed, 95, style),
        inversePrimary = ExpressiveColorUtils.expressiveTone(seed, 80, style, ColorRole.Primary),

        // Others
        scrim = Color.Black,
        surfaceTint = ExpressiveColorUtils.expressiveTone(seed, 35, style, ColorRole.Primary)
    )
}

/**
 * Material 3 Expressive 深色配色方案
 */
private fun generateExpressiveDarkColorScheme(seed: Color, style: PaletteStyle): androidx.compose.material3.ColorScheme {
    val tertiary = ExpressiveColorUtils.tertiaryColor(seed, style)

    return darkColorScheme(
        // Primary - 深色模式下更亮
        primary = ExpressiveColorUtils.expressiveTone(seed, 80, style, ColorRole.Primary),
        onPrimary = ExpressiveColorUtils.expressiveTone(seed, 20, style, ColorRole.Primary),
        primaryContainer = ExpressiveColorUtils.expressiveTone(seed, 30, style, ColorRole.Primary),
        onPrimaryContainer = ExpressiveColorUtils.expressiveTone(seed, 90, style, ColorRole.Primary),

        // Secondary
        secondary = ExpressiveColorUtils.expressiveTone(seed, 80, style, ColorRole.Secondary),
        onSecondary = ExpressiveColorUtils.expressiveTone(seed, 20, style, ColorRole.Secondary),
        secondaryContainer = ExpressiveColorUtils.expressiveTone(seed, 30, style, ColorRole.Secondary),
        onSecondaryContainer = ExpressiveColorUtils.expressiveTone(seed, 90, style, ColorRole.Secondary),

        // Tertiary
        tertiary = ExpressiveColorUtils.expressiveTone(tertiary, 80, style, ColorRole.Tertiary),
        onTertiary = ExpressiveColorUtils.expressiveTone(tertiary, 20, style, ColorRole.Tertiary),
        tertiaryContainer = ExpressiveColorUtils.expressiveTone(tertiary, 30, style, ColorRole.Tertiary),
        onTertiaryContainer = ExpressiveColorUtils.expressiveTone(tertiary, 90, style, ColorRole.Tertiary),

        // Error
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),

        // Background & Surface - 深色模式：背景暗，表面更暗形成层次
        background = ExpressiveColorUtils.neutralTone(seed, 6, style),
        onBackground = ExpressiveColorUtils.neutralTone(seed, 90, style),

        surface = ExpressiveColorUtils.neutralTone(seed, 6, style),
        onSurface = ExpressiveColorUtils.neutralTone(seed, 90, style),

        // Surface层级 - 深色模式下从亮到暗
        surfaceDim = ExpressiveColorUtils.neutralTone(seed, 6, style),
        surfaceBright = ExpressiveColorUtils.neutralTone(seed, 24, style),

        // Surface Container层级 - 深色模式：Lowest最暗，Highest最亮
        surfaceContainerLowest = ExpressiveColorUtils.neutralTone(seed, 4, style),
        surfaceContainerLow = ExpressiveColorUtils.neutralTone(seed, 10, style),
        surfaceContainer = ExpressiveColorUtils.neutralTone(seed, 12, style),
        surfaceContainerHigh = ExpressiveColorUtils.neutralTone(seed, 17, style),
        surfaceContainerHighest = ExpressiveColorUtils.neutralTone(seed, 24, style),

        // Surface Variant
        surfaceVariant = ExpressiveColorUtils.neutralVariantTone(seed, 30, style),
        onSurfaceVariant = ExpressiveColorUtils.neutralVariantTone(seed, 80, style),

        // Outline
        outline = ExpressiveColorUtils.neutralVariantTone(seed, 60, style),
        outlineVariant = ExpressiveColorUtils.neutralVariantTone(seed, 30, style),

        // Inverse
        inverseSurface = ExpressiveColorUtils.neutralTone(seed, 90, style),
        inverseOnSurface = ExpressiveColorUtils.neutralTone(seed, 20, style),
        inversePrimary = ExpressiveColorUtils.expressiveTone(seed, 40, style, ColorRole.Primary),

        // Others
        scrim = Color.Black,
        surfaceTint = ExpressiveColorUtils.expressiveTone(seed, 80, style, ColorRole.Primary)
    )
}
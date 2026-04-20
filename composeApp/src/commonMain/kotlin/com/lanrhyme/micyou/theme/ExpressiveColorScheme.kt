package com.lanrhyme.micyou.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * Material 3 Expressive (2025) 颜色方案生成器
 * 支持多种调色板风格：Tonal, Expressive, Vibrant, Monochrome, Rainbow
 *
 * M3 Expressive 规范核心：
 * - Primary chroma: 48 (高饱和度)
 * - Secondary chroma: 16 (中等饱和度)
 * - Tertiary chroma: 24 (偏中高饱和度)
 * - Neutral chroma: 4 (微弱色彩倾向)
 * - Neutral Variant chroma: 8 (稍强色彩倾向)
 */

enum class PaletteStyle {
    Tonal,       // 经典M3色调风格 - 标准chroma值
    Neutral,     // 中性风格 - 极低chroma
    Vibrant,     // 鲜艳风格 - 最高chroma
    Expressive,  // 表达性风格 - M3 Expressive标准chroma
    Rainbow,     // 彩虹风格 - 色相偏移
    FruitSalad,  // 水果沙拉风格 - 双色调高对比
    Monochrome,  // 单色风格 - 无chroma
    Fidelity,    // 高保真风格 - 保持原色chroma
    Content      // 内容导向风格 - 适中原色chroma
}

object ExpressiveColorUtils {
    // M3 Expressive 标准chroma值 (基于HCT色彩空间)
    private const val EXPRESSIVE_PRIMARY_CHROMA = 48f
    private const val EXPRESSIVE_SECONDARY_CHROMA = 16f
    private const val EXPRESSIVE_TERTIARY_CHROMA = 24f
    private const val EXPRESSIVE_NEUTRAL_CHROMA = 4f
    private const val EXPRESSIVE_NEUTRAL_VARIANT_CHROMA = 8f

    // M3 Tonal 标准chroma值
    private const val TONAL_PRIMARY_CHROMA = 36f
    private const val TONAL_SECONDARY_CHROMA = 16f
    private const val TONAL_TERTIARY_CHROMA = 24f
    private const val TONAL_NEUTRAL_CHROMA = 4f
    private const val TONAL_NEUTRAL_VARIANT_CHROMA = 6f

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
     * 将chroma值转换为HSL饱和度
     * Chroma是HCT色彩空间的色度量，需要转换为HSL的饱和度
     */
    private fun chromaToHslSaturation(chroma: Float, tone: Int): Float {
        // chroma范围是0-120，tone范围是0-100
        // 转换公式：s = chroma / (100 - abs(2*l - 100))，其中l = tone
        val l = tone / 100f
        val maxChroma = 100f * (1f - abs(2f * l - 1f))
        return (chroma / maxChroma).coerceIn(0f, 1f)
    }

    /**
     * Expressive风格色调生成 - 使用标准M3 chroma值
     * 关键修复：不再依赖种子颜色饱和度，使用固定chroma
     */
    fun expressiveTone(
        seedColor: Color,
        tone: Int,
        paletteStyle: PaletteStyle = PaletteStyle.Expressive,
        colorRole: ColorRole = ColorRole.Primary
    ): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val hue = hsl[0]

        // 使用固定chroma值，而非依赖种子颜色饱和度
        val chroma = getStandardChroma(paletteStyle, colorRole)
        val saturation = chromaToHslSaturation(chroma, tone)

        return Color(hslToColor(hue, saturation, tone / 100f))
    }

    /**
     * 中性色调生成 - Expressive风格下使用固定低chroma
     * 关键修复：中性色应该有固定的微弱色彩倾向，而非基于种子颜色
     */
    fun neutralTone(
        seedColor: Color,
        tone: Int,
        paletteStyle: PaletteStyle = PaletteStyle.Expressive
    ): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val hue = hsl[0]

        // 使用固定的中性chroma值
        val neutralChroma = when (paletteStyle) {
            PaletteStyle.Monochrome -> 0f
            PaletteStyle.Neutral -> 2f
            PaletteStyle.Tonal -> TONAL_NEUTRAL_CHROMA
            PaletteStyle.Fidelity, PaletteStyle.Content -> 6f
            PaletteStyle.FruitSalad -> 10f
            PaletteStyle.Expressive -> EXPRESSIVE_NEUTRAL_CHROMA
            PaletteStyle.Vibrant -> 12f
            PaletteStyle.Rainbow -> 8f
        }

        val saturation = chromaToHslSaturation(neutralChroma, tone)
        return Color(hslToColor(hue, saturation, tone / 100f))
    }

    /**
     * 中性变体色调生成 - 用于surfaceVariant
     */
    fun neutralVariantTone(
        seedColor: Color,
        tone: Int,
        paletteStyle: PaletteStyle = PaletteStyle.Expressive
    ): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val hue = hsl[0]

        val variantChroma = when (paletteStyle) {
            PaletteStyle.Monochrome -> 0f
            PaletteStyle.Neutral -> 2f
            PaletteStyle.Tonal -> TONAL_NEUTRAL_VARIANT_CHROMA
            PaletteStyle.Fidelity, PaletteStyle.Content -> 10f
            PaletteStyle.FruitSalad -> 12f
            PaletteStyle.Expressive -> EXPRESSIVE_NEUTRAL_VARIANT_CHROMA
            PaletteStyle.Vibrant -> 16f
            PaletteStyle.Rainbow -> 10f
        }

        val saturation = chromaToHslSaturation(variantChroma, tone)
        return Color(hslToColor(hue, saturation, tone / 100f))
    }

    /**
     * 第三颜色生成 - 根据PaletteStyle进行色相偏移
     */
    fun tertiaryColor(seedColor: Color, paletteStyle: PaletteStyle = PaletteStyle.Expressive): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val hueOffset = when (paletteStyle) {
            PaletteStyle.Tonal, PaletteStyle.Neutral, PaletteStyle.Fidelity -> 60f
            PaletteStyle.Expressive, PaletteStyle.FruitSalad -> 90f
            PaletteStyle.Vibrant -> 120f
            PaletteStyle.Monochrome, PaletteStyle.Content -> 30f
            PaletteStyle.Rainbow -> 180f
        }
        val newHue = (hsl[0] + hueOffset) % 360f

        // Tertiary使用自己的chroma，而非继承种子颜色的饱和度
        val tertiaryChroma = getStandardChroma(paletteStyle, ColorRole.Tertiary)
        val saturation = chromaToHslSaturation(tertiaryChroma, 50) // 用tone=50作为基准

        return Color(hslToColor(newHue, saturation, hsl[2]))
    }

    /**
     * 获取标准chroma值 - 基于M3规范
     */
    private fun getStandardChroma(paletteStyle: PaletteStyle, colorRole: ColorRole): Float {
        return when (paletteStyle) {
            PaletteStyle.Expressive -> when (colorRole) {
                ColorRole.Primary -> EXPRESSIVE_PRIMARY_CHROMA
                ColorRole.Secondary -> EXPRESSIVE_SECONDARY_CHROMA
                ColorRole.Tertiary -> EXPRESSIVE_TERTIARY_CHROMA
            }
            PaletteStyle.Tonal -> when (colorRole) {
                ColorRole.Primary -> TONAL_PRIMARY_CHROMA
                ColorRole.Secondary -> TONAL_SECONDARY_CHROMA
                ColorRole.Tertiary -> TONAL_TERTIARY_CHROMA
            }
            PaletteStyle.Neutral -> when (colorRole) {
                ColorRole.Primary -> 12f
                ColorRole.Secondary -> 8f
                ColorRole.Tertiary -> 10f
            }
            PaletteStyle.Vibrant -> when (colorRole) {
                ColorRole.Primary -> 72f  // 最高chroma
                ColorRole.Secondary -> 48f
                ColorRole.Tertiary -> 56f
            }
            PaletteStyle.FruitSalad -> when (colorRole) {
                ColorRole.Primary -> 64f
                ColorRole.Secondary -> 48f
                ColorRole.Tertiary -> 56f
            }
            PaletteStyle.Monochrome -> when (colorRole) {
                ColorRole.Primary -> 8f
                ColorRole.Secondary -> 4f
                ColorRole.Tertiary -> 6f
            }
            PaletteStyle.Rainbow -> when (colorRole) {
                ColorRole.Primary -> 48f
                ColorRole.Secondary -> 32f
                ColorRole.Tertiary -> 40f
            }
            PaletteStyle.Fidelity -> when (colorRole) {
                ColorRole.Primary -> 48f  // 保持较高chroma
                ColorRole.Secondary -> 24f
                ColorRole.Tertiary -> 32f
            }
            PaletteStyle.Content -> when (colorRole) {
                ColorRole.Primary -> 36f
                ColorRole.Secondary -> 20f
                ColorRole.Tertiary -> 28f
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
    paletteStyle: PaletteStyle = PaletteStyle.Expressive
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

        // Background & Surface - 深色模式：背景暗，表面更暗形成层次 (稍微提亮以增加色度可见性)
        background = ExpressiveColorUtils.neutralTone(seed, 10, style),
        onBackground = ExpressiveColorUtils.neutralTone(seed, 90, style),

        surface = ExpressiveColorUtils.neutralTone(seed, 10, style),
        onSurface = ExpressiveColorUtils.neutralTone(seed, 90, style),

        // Surface层级 - 深色模式下从亮到暗
        surfaceDim = ExpressiveColorUtils.neutralTone(seed, 10, style),
        surfaceBright = ExpressiveColorUtils.neutralTone(seed, 28, style),

        // Surface Container层级 - 深色模式：Lowest最暗，Highest最亮
        surfaceContainerLowest = ExpressiveColorUtils.neutralTone(seed, 6, style),
        surfaceContainerLow = ExpressiveColorUtils.neutralTone(seed, 14, style),
        surfaceContainer = ExpressiveColorUtils.neutralTone(seed, 16, style),
        surfaceContainerHigh = ExpressiveColorUtils.neutralTone(seed, 21, style),
        surfaceContainerHighest = ExpressiveColorUtils.neutralTone(seed, 28, style),

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
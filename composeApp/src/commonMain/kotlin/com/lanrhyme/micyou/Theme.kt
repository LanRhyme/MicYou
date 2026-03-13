package com.lanrhyme.micyou

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

//  Material Design 3 颜色工具 - 简化实现
object MD3ColorUtils {
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
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or (((r + m) * 255f).toInt() shl 16) or
                (((g + m) * 255f).toInt() shl 8) or ((b + m) * 255f).toInt()
    }

    fun tone(seedColor: Color, tone: Int, chromaMultiplier: Float = 1f): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        return Color(hslToColor(hsl[0], (hsl[1] * chromaMultiplier).coerceIn(0f, 1f), tone / 100f))
    }

    fun neutralTone(tone: Int) = Color(hslToColor(0f, 0f, tone / 100f))

    fun neutralVariantTone(seedColor: Color, tone: Int): Color {
        val h = colorToHSL(seedColor.toArgb())[0]
        return Color(hslToColor(h, 0.04f, tone / 100f))
    }
}

// M3 预设种子颜色
object MD3SeedColors {
    val Blue = Color(0xFF4285F4)           // Google Blue (默认)
    val Purple = Color(0xFF6750A4)         // Material Purple
    val Pink = Color(0xFFD0BCFF)           // 淡紫色
    val Red = Color(0xFFF44336)            // 红色
    val Orange = Color(0xFFFF9800)         // 橙色
    val Yellow = Color(0xFFFFEB3B)         // 黄色
    val Green = Color(0xFF4CAF50)          // 绿色
    val Teal = Color(0xFF009688)           // 青绿色
    val Cyan = Color(0xFF00BCD4)           // 青色
    val DeepPurple = Color(0xFF9C27B0)     // 深紫色
    val Indigo = Color(0xFF3F51B5)         // 靛蓝色

    val allColors = listOf(
        Blue, Purple, Pink, Red, Orange, Yellow, Green, Teal, Cyan, DeepPurple, Indigo
    )
}

// 生成 M3 标准配色方案
fun generateMD3ColorScheme(seedColor: Color, isDark: Boolean): androidx.compose.material3.ColorScheme {
    return if (isDark) {
        generateDarkColorScheme(seedColor)
    } else {
        generateLightColorScheme(seedColor)
    }
}

private fun generateLightColorScheme(seed: Color): androidx.compose.material3.ColorScheme {
    return lightColorScheme(
        // Primary - 主要操作颜色
        primary = MD3ColorUtils.tone(seed, 40),
        onPrimary = MD3ColorUtils.tone(seed, 100),
        primaryContainer = MD3ColorUtils.tone(seed, 90, 0.7f),
        onPrimaryContainer = MD3ColorUtils.tone(seed, 10),

        // Secondary - 辅助颜色，降低饱和度
        secondary = MD3ColorUtils.tone(seed, 40, 0.5f),
        onSecondary = MD3ColorUtils.tone(seed, 100),
        secondaryContainer = MD3ColorUtils.tone(seed, 90, 0.3f),
        onSecondaryContainer = MD3ColorUtils.tone(seed, 10, 0.5f),

        // Tertiary - 第三颜色，色相偏移
        tertiary = MD3ColorUtils.tone(tertiaryHue(seed), 40, 0.6f),
        onTertiary = MD3ColorUtils.tone(tertiaryHue(seed), 100),
        tertiaryContainer = MD3ColorUtils.tone(tertiaryHue(seed), 90, 0.4f),
        onTertiaryContainer = MD3ColorUtils.tone(tertiaryHue(seed), 10, 0.6f),

        // Error - 错误颜色
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),

        // Background & Surface
        background = MD3ColorUtils.neutralTone(98),
        onBackground = MD3ColorUtils.neutralTone(10),
        surface = MD3ColorUtils.neutralTone(98),
        onSurface = MD3ColorUtils.neutralTone(10),

        // Surface Variants
        surfaceVariant = MD3ColorUtils.neutralVariantTone(seed, 90),
        onSurfaceVariant = MD3ColorUtils.neutralVariantTone(seed, 30),
        surfaceContainer = MD3ColorUtils.neutralTone(94),
        surfaceContainerLow = MD3ColorUtils.neutralTone(96),
        surfaceContainerHigh = MD3ColorUtils.neutralTone(92),
        surfaceContainerHighest = MD3ColorUtils.neutralTone(90),
        surfaceContainerLowest = MD3ColorUtils.neutralTone(100),

        // Outline
        outline = MD3ColorUtils.neutralVariantTone(seed, 50),
        outlineVariant = MD3ColorUtils.neutralVariantTone(seed, 80),

        // Inverse
        inverseSurface = MD3ColorUtils.neutralTone(20),
        inverseOnSurface = MD3ColorUtils.neutralTone(95),
        inversePrimary = MD3ColorUtils.tone(seed, 80),

        // Others
        scrim = Color.Black,
        surfaceTint = MD3ColorUtils.tone(seed, 40)
    )
}

private fun generateDarkColorScheme(seed: Color): androidx.compose.material3.ColorScheme {
    return darkColorScheme(
        // Primary - 主要操作颜色
        primary = MD3ColorUtils.tone(seed, 80),
        onPrimary = MD3ColorUtils.tone(seed, 20),
        primaryContainer = MD3ColorUtils.tone(seed, 30, 0.8f),
        onPrimaryContainer = MD3ColorUtils.tone(seed, 90),

        // Secondary - 辅助颜色
        secondary = MD3ColorUtils.tone(seed, 80, 0.5f),
        onSecondary = MD3ColorUtils.tone(seed, 20),
        secondaryContainer = MD3ColorUtils.tone(seed, 30, 0.3f),
        onSecondaryContainer = MD3ColorUtils.tone(seed, 90, 0.5f),

        // Tertiary - 第三颜色
        tertiary = MD3ColorUtils.tone(tertiaryHue(seed), 80, 0.6f),
        onTertiary = MD3ColorUtils.tone(tertiaryHue(seed), 20),
        tertiaryContainer = MD3ColorUtils.tone(tertiaryHue(seed), 30, 0.4f),
        onTertiaryContainer = MD3ColorUtils.tone(tertiaryHue(seed), 90, 0.6f),

        // Error - 错误颜色
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),

        // Background & Surface
        background = MD3ColorUtils.neutralTone(6),
        onBackground = MD3ColorUtils.neutralTone(90),
        surface = MD3ColorUtils.neutralTone(6),
        onSurface = MD3ColorUtils.neutralTone(90),

        // Surface Variants
        surfaceVariant = MD3ColorUtils.neutralVariantTone(seed, 30),
        onSurfaceVariant = MD3ColorUtils.neutralVariantTone(seed, 80),
        surfaceContainer = MD3ColorUtils.neutralTone(12),
        surfaceContainerLow = MD3ColorUtils.neutralTone(10),
        surfaceContainerHigh = MD3ColorUtils.neutralTone(17),
        surfaceContainerHighest = MD3ColorUtils.neutralTone(22),
        surfaceContainerLowest = MD3ColorUtils.neutralTone(4),

        // Outline
        outline = MD3ColorUtils.neutralVariantTone(seed, 60),
        outlineVariant = MD3ColorUtils.neutralVariantTone(seed, 30),

        // Inverse
        inverseSurface = MD3ColorUtils.neutralTone(90),
        inverseOnSurface = MD3ColorUtils.neutralTone(20),
        inversePrimary = MD3ColorUtils.tone(seed, 40),

        // Others
        scrim = Color.Black,
        surfaceTint = MD3ColorUtils.tone(seed, 80)
    )
}

// 生成第三颜色的色相（偏移60度）
private fun tertiaryHue(seed: Color): Color {
    val hsl = MD3ColorUtils.colorToHSL(seed.toArgb())
    val newHue = (hsl[0] + 60f) % 360f
    val s = hsl[1]
    val l = hsl[2]
    return Color(MD3ColorUtils.hslToColor(newHue, s, l))
}

// 应用 OLED 纯黑背景
private fun androidx.compose.material3.ColorScheme.withOledDarkBackground(): androidx.compose.material3.ColorScheme {
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

enum class ThemeMode {
    System, Light, Dark
}

@Composable
fun isDarkThemeActive(themeMode: ThemeMode): Boolean {
    return when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

// 默认种子颜色 - Google Blue
val DefaultSeedColor = MD3SeedColors.Blue

// M3 应用主题
@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.System,
    seedColor: Color = DefaultSeedColor,
    useDynamicColor: Boolean = false,
    oledPureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = isDarkThemeActive(themeMode)
    val dynamicScheme = if (useDynamicColor) getDynamicColorScheme(isDark) else null
    val baseColorScheme = dynamicScheme ?: generateMD3ColorScheme(seedColor, isDark)
    val targetColorScheme = if (isDark && oledPureBlack) baseColorScheme.withOledDarkBackground() else baseColorScheme

    MaterialTheme(colorScheme = targetColorScheme, content = content)
}

// 保留旧的辅助函数以兼容现有代码
fun colorToHSV(color: Int, hsv: FloatArray) {
    val hsl = MD3ColorUtils.colorToHSL(color)
    hsv[0] = hsl[0]
    hsv[1] = hsl[1]
    hsv[2] = hsl[2]
}

fun hsvToColor(hsv: FloatArray): Int {
    return MD3ColorUtils.hslToColor(hsv[0], hsv[1], hsv[2])
}

fun generateColorScheme(seed: Color, isDark: Boolean): androidx.compose.material3.ColorScheme {
    return generateMD3ColorScheme(seed, isDark)
} 

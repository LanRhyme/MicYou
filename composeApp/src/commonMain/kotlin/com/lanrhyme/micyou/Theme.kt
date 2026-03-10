package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Material Design 3 颜色工具
 * 
 * 使用 HCT (Hue, Chroma, Tone) 色彩空间的简化实现
 * M3 使用 Tone (色调) 来生成颜色变体，范围 0-100
 */
object MD3ColorUtils {
    /**
     * 将 ARGB 颜色转换为 HSL
     */
    fun colorToHSL(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        var h = 0f
        var s = 0f
        val l = (max + min) / 2f

        if (delta != 0f) {
            s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
            when (max) {
                r -> h = ((g - b) / delta + if (g < b) 6f else 0f) / 6f
                g -> h = ((b - r) / delta + 2f) / 6f
                b -> h = ((r - g) / delta + 4f) / 6f
            }
        }

        return floatArrayOf(h * 360f, s, l)
    }

    /**
     * 将 HSL 转换为 ARGB 颜色
     */
    fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f

        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return (0xFF shl 24) or
                (((r + m) * 255f).toInt() shl 16) or
                (((g + m) * 255f).toInt() shl 8) or
                ((b + m) * 255f).toInt()
    }

    /**
     * 从种子颜色生成指定色调的颜色
     * M3 使用 Tone (0-100) 来控制亮度
     */
    fun tone(seedColor: Color, tone: Int, chromaMultiplier: Float = 1f): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val h = hsl[0]
        // 降低饱和度以获得更柔和的颜色
        val s = (hsl[1] * chromaMultiplier).coerceIn(0f, 1f)
        // 将 tone (0-100) 转换为 lightness (0-1)
        val l = tone / 100f

        return Color(hslToColor(h, s, l))
    }

    /**
     * 生成中性色（低饱和度）
     */
    fun neutralTone(tone: Int): Color {
        // 中性色使用非常低的饱和度
        val l = tone / 100f
        return Color(hslToColor(0f, 0f, l))
    }

    /**
     * 生成中性变体色（略带色调）
     */
    fun neutralVariantTone(seedColor: Color, tone: Int): Color {
        val hsl = colorToHSL(seedColor.toArgb())
        val h = hsl[0]
        // 非常低的饱和度，但保留一点色调
        val s = 0.04f
        val l = tone / 100f
        return Color(hslToColor(h, s, l))
    }
}

/**
 * M3 预设种子颜色
 * 这些颜色经过优化，可以生成和谐的配色方案
 */
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

/**
 * 生成 M3 标准配色方案
 * 
 * 使用 Tone-based 颜色生成，符合 Material Design 3 规范
 */
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

/**
 * 生成第三颜色的色相（偏移60度）
 */
private fun tertiaryHue(seed: Color): Color {
    val hsl = MD3ColorUtils.colorToHSL(seed.toArgb())
    val newHue = (hsl[0] + 60f) % 360f
    val s = hsl[1]
    val l = hsl[2]
    return Color(MD3ColorUtils.hslToColor(newHue, s, l))
}

/**
 * 应用 OLED 纯黑背景
 */
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

/**
 * 默认种子颜色 - Google Blue
 */
val DefaultSeedColor = MD3SeedColors.Blue

/**
 * 主题动画配置
 */
private val themeAnimationSpec: AnimationSpec<Color> = tween(durationMillis = 400)

/**
 * M3 应用主题
 * 
 * @param themeMode 主题模式
 * @param seedColor 种子颜色，用于生成配色方案
 * @param useDynamicColor 是否使用动态颜色（Android 12+）
 * @param oledPureBlack 是否使用 OLED 纯黑背景
 */
@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.System,
    seedColor: Color = DefaultSeedColor,
    useDynamicColor: Boolean = false,
    oledPureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = isDarkThemeActive(themeMode)

    // 动态颜色（仅 Android 支持）
    val dynamicScheme = if (useDynamicColor) getDynamicColorScheme(isDark) else null

    // 基础配色方案
    val baseColorScheme = dynamicScheme ?: generateMD3ColorScheme(seedColor, isDark)

    // OLED 纯黑背景
    val targetColorScheme = if (isDark && oledPureBlack) {
        baseColorScheme.withOledDarkBackground()
    } else {
        baseColorScheme
    }

    // 为主题颜色添加动画过渡
    val animatedColorScheme = animateColorScheme(targetColorScheme, isDark)

    MaterialTheme(
        colorScheme = animatedColorScheme,
        shapes = getMD3Shapes(),
        content = content
    )
}

/**
 * 为配色方案添加动画过渡
 */
@Composable
private fun animateColorScheme(
    target: androidx.compose.material3.ColorScheme,
    isDark: Boolean
): androidx.compose.material3.ColorScheme {
    val primary by animateColorAsState(target.primary, themeAnimationSpec)
    val onPrimary by animateColorAsState(target.onPrimary, themeAnimationSpec)
    val primaryContainer by animateColorAsState(target.primaryContainer, themeAnimationSpec)
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, themeAnimationSpec)
    val secondary by animateColorAsState(target.secondary, themeAnimationSpec)
    val onSecondary by animateColorAsState(target.onSecondary, themeAnimationSpec)
    val secondaryContainer by animateColorAsState(target.secondaryContainer, themeAnimationSpec)
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, themeAnimationSpec)
    val tertiary by animateColorAsState(target.tertiary, themeAnimationSpec)
    val onTertiary by animateColorAsState(target.onTertiary, themeAnimationSpec)
    val tertiaryContainer by animateColorAsState(target.tertiaryContainer, themeAnimationSpec)
    val onTertiaryContainer by animateColorAsState(target.onTertiaryContainer, themeAnimationSpec)
    val background by animateColorAsState(target.background, themeAnimationSpec)
    val onBackground by animateColorAsState(target.onBackground, themeAnimationSpec)
    val surface by animateColorAsState(target.surface, themeAnimationSpec)
    val onSurface by animateColorAsState(target.onSurface, themeAnimationSpec)
    val surfaceVariant by animateColorAsState(target.surfaceVariant, themeAnimationSpec)
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, themeAnimationSpec)
    val surfaceContainer by animateColorAsState(target.surfaceContainer, themeAnimationSpec)
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, themeAnimationSpec)
    val error by animateColorAsState(target.error, themeAnimationSpec)
    val onError by animateColorAsState(target.onError, themeAnimationSpec)
    val errorContainer by animateColorAsState(target.errorContainer, themeAnimationSpec)
    val onErrorContainer by animateColorAsState(target.onErrorContainer, themeAnimationSpec)
    val outline by animateColorAsState(target.outline, themeAnimationSpec)
    val outlineVariant by animateColorAsState(target.outlineVariant, themeAnimationSpec)
    val inverseSurface by animateColorAsState(target.inverseSurface, themeAnimationSpec)
    val inverseOnSurface by animateColorAsState(target.inverseOnSurface, themeAnimationSpec)
    val inversePrimary by animateColorAsState(target.inversePrimary, themeAnimationSpec)
    val surfaceTint by animateColorAsState(target.surfaceTint, themeAnimationSpec)
    val scrim by animateColorAsState(target.scrim, themeAnimationSpec)

    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
            surfaceTint = surfaceTint,
            scrim = scrim
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
            surfaceTint = surfaceTint,
            scrim = scrim
        )
    }
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
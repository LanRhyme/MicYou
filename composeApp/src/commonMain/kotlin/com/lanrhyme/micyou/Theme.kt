package com.lanrhyme.micyou

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lanrhyme.micyou.theme.*

// 导出PaletteStyle供外部使用
typealias AppPaletteStyle = PaletteStyle

// M3 Expressive 预设种子颜色 - 精选现代配色
object MD3SeedColors {
    // 核心推荐色 - 符合M3 Expressive风格
    val OceanBlue = Color(0xFF1565C0)      // 海洋蓝 - 深邃优雅
    val M3Purple = Color(0xFF6750A4)       // Material Purple - 经典M3紫
    val RosePink = Color(0xFFE91E63)       // 玫瑰粉 - 温暖浪漫
    val ForestGreen = Color(0xFF2E7D32)    // 森林绿 - 自然清新
    val SunsetOrange = Color(0xFFFF5722)   // 日落橙 - 活力温暖
    val DeepTeal = Color(0xFF00695C)       // 深青绿 - 稳重现代

    // 进阶选择色
    val MidnightIndigo = Color(0xFF283593) // 深靛蓝 - 夜空神秘
    val CoralRed = Color(0xFFD32F2F)       // 珊瑚红 - 热情明亮
    val GoldenAmber = Color(0xFFFF8F00)    // 金琥珀 - 温暖质感
    val LavenderViolet = Color(0xFF7B1FA2) // 薰衣草紫 - 优雅神秘

    val allColors = listOf(
        OceanBlue, M3Purple, RosePink, ForestGreen, SunsetOrange, DeepTeal,
        MidnightIndigo, CoralRed, GoldenAmber, LavenderViolet
    )
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
val DefaultSeedColor = MD3SeedColors.OceanBlue

// 默认调色板风格
val DefaultPaletteStyle = PaletteStyle.Expressive

/**
 * Material 3 Expressive 应用主题
 * 支持调色板风格和Expressive形状
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
    val dynamicScheme = if (useDynamicColor) getDynamicColorScheme(isDark) else null

    // 使用Expressive配色方案生成器
    val baseColorScheme = dynamicScheme ?: generateExpressiveColorScheme(seedColor, isDark, paletteStyle)
    val targetColorScheme = if (isDark && oledPureBlack) baseColorScheme.withOledDarkBackground() else baseColorScheme

    // 应用Expressive形状
    val shapes = if (useExpressiveShapes) ExpressiveShapes else MaterialTheme.shapes

    MaterialTheme(
        colorScheme = targetColorScheme,
        shapes = shapes,
        content = content
    )
}

// 保留旧的辅助函数以兼容现有代码
fun colorToHSV(color: Int, hsv: FloatArray) {
    val hsl = ExpressiveColorUtils.colorToHSL(color)
    hsv[0] = hsl[0]
    hsv[1] = hsl[1]
    hsv[2] = hsl[2]
}

fun hsvToColor(hsv: FloatArray): Int {
    return ExpressiveColorUtils.hslToColor(hsv[0], hsv[1], hsv[2])
}

fun generateColorScheme(seed: Color, isDark: Boolean): androidx.compose.material3.ColorScheme {
    return generateExpressiveColorScheme(seed, isDark)
}
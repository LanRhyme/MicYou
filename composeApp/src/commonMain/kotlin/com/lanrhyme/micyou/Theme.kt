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
import androidx.compose.ui.unit.dp

// Helper class to handle Color conversions in Common code
// since android.graphics.Color is not available in commonMain directly for all targets without expect/actual,
// but here we are in commonMain trying to use Android APIs which causes the issue.
// We should implement a pure Kotlin Color utils or use expect/actual.
// For simplicity in this fix, we will implement a basic HSV converter in Kotlin.

fun colorToHSV(color: Int, hsv: FloatArray) {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    var h = 0f
    var s = 0f
    val v = max

    if (max != 0f) {
        s = delta / max
    }

    if (delta != 0f) {
        if (r == max) {
            h = (g - b) / delta
        } else if (g == max) {
            h = 2f + (b - r) / delta
        } else {
            h = 4f + (r - g) / delta
        }
        h *= 60f
        if (h < 0) h += 360f
        if (h >= 360f) h -= 360f
    }

    hsv[0] = h
    hsv[1] = s
    hsv[2] = v
}

fun hsvToColor(hsv: FloatArray): Int {
    val h = hsv[0]
    val s = hsv[1]
    val v = hsv[2]

    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
    val m = v - c

    var r = 0f
    var g = 0f
    var b = 0f

    val hueSegment = (h / 60).toInt() % 6
    when (hueSegment) {
        0 -> { r = c; g = x; b = 0f }
        1 -> { r = x; g = c; b = 0f }
        2 -> { r = 0f; g = c; b = x }
        3 -> { r = 0f; g = x; b = c }
        4 -> { r = x; g = 0f; b = c }
        5 -> { r = c; g = 0f; b = x }
    }

    val ir = ((r + m) * 255).toInt()
    val ig = ((g + m) * 255).toInt()
    val ib = ((b + m) * 255).toInt()

    return (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
}

// Improved color generation logic
fun generateColorScheme(seed: Color, isDark: Boolean): androidx.compose.material3.ColorScheme {
    // In a real implementation, you would use HCT color space for tonal palettes.
    // Here we use a simplified approach to generate a more harmonious palette.
    
    // Convert to HSV to manipulate brightness/saturation
    val hsv = FloatArray(3)
    colorToHSV(seed.toArgb(), hsv)
    
    // Helper to create tonal variations
    fun tone(hsv: FloatArray, satFactor: Float, valFactor: Float): Color {
        val newHsv = hsv.clone()
        newHsv[1] = (newHsv[1] * satFactor).coerceIn(0f, 1f)
        newHsv[2] = (newHsv[2] * valFactor).coerceIn(0f, 1f)
        return Color(hsvToColor(newHsv))
    }

    // Helper for surface colors (neutral)
    fun neutral(hsv: FloatArray, value: Float): Color {
        val newHsv = hsv.clone()
        newHsv[1] *= 0.1f // Very low saturation
        newHsv[2] = value
        return Color(hsvToColor(newHsv))
    }

    return if (isDark) {
        darkColorScheme(
            primary = tone(hsv, 0.8f, 1.0f), // P80
            onPrimary = tone(hsv, 1.0f, 0.2f), // P20
            primaryContainer = tone(hsv, 1.0f, 0.3f), // P30
            onPrimaryContainer = tone(hsv, 0.6f, 0.9f), // P90
            
            secondary = tone(hsv, 0.4f, 0.9f), // Lower saturation for secondary
            onSecondary = tone(hsv, 0.4f, 0.2f),
            secondaryContainer = tone(hsv, 0.4f, 0.3f),
            onSecondaryContainer = tone(hsv, 0.4f, 0.9f),
            
            tertiary = tone(hsv, 0.6f, 0.9f).let { 
                // Shift hue for tertiary
                val tHsv = FloatArray(3); colorToHSV(it.toArgb(), tHsv)
                tHsv[0] = (tHsv[0] + 60) % 360
                Color(hsvToColor(tHsv))
            },
            
            surface = neutral(hsv, 0.1f),
            onSurface = neutral(hsv, 0.9f),
            surfaceContainer = neutral(hsv, 0.12f),
            surfaceContainerHigh = neutral(hsv, 0.15f),
            background = neutral(hsv, 0.1f),
            onBackground = neutral(hsv, 0.9f),
            
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        )
    } else {
        lightColorScheme(
            primary = tone(hsv, 1.0f, 0.6f), // P40
            onPrimary = Color.White,
            primaryContainer = tone(hsv, 0.6f, 0.9f), // P90
            onPrimaryContainer = tone(hsv, 1.0f, 0.3f), // P10
            
            secondary = tone(hsv, 0.5f, 0.5f),
            onSecondary = Color.White,
            secondaryContainer = tone(hsv, 0.3f, 0.9f),
            onSecondaryContainer = tone(hsv, 0.5f, 0.2f),
            
            tertiary = tone(hsv, 0.7f, 0.5f).let {
                val tHsv = FloatArray(3); colorToHSV(it.toArgb(), tHsv)
                tHsv[0] = (tHsv[0] + 60) % 360
                Color(hsvToColor(tHsv))
            },
            
            surface = neutral(hsv, 0.99f),
            onSurface = neutral(hsv, 0.1f),
            surfaceContainer = neutral(hsv, 0.96f),
            surfaceContainerHigh = neutral(hsv, 0.92f),
            background = neutral(hsv, 0.99f),
            onBackground = neutral(hsv, 0.1f),
            
            error = Color(0xFFBA1A1A),
            onError = Color.White
        )
    }
}

enum class ThemeMode {
    System, Light, Dark
}

val DefaultSeedColor = Color(0xFF4285F4) // Google Blue - Material Design 3 推荐蓝色

val AppShapes = androidx.compose.material3.Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
)

// 主题动画配置
private val themeAnimationSpec: AnimationSpec<Color> = tween(durationMillis = 400)

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.System,
    seedColor: Color = DefaultSeedColor,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val dynamicScheme = if (useDynamicColor) getDynamicColorScheme(isDark) else null
    val targetColorScheme = dynamicScheme ?: generateColorScheme(seedColor, isDark)

    // 为主题颜色添加动画过渡
    val animatedPrimary by animateColorAsState(targetColorScheme.primary, themeAnimationSpec)
    val animatedOnPrimary by animateColorAsState(targetColorScheme.onPrimary, themeAnimationSpec)
    val animatedPrimaryContainer by animateColorAsState(targetColorScheme.primaryContainer, themeAnimationSpec)
    val animatedOnPrimaryContainer by animateColorAsState(targetColorScheme.onPrimaryContainer, themeAnimationSpec)
    val animatedSecondary by animateColorAsState(targetColorScheme.secondary, themeAnimationSpec)
    val animatedOnSecondary by animateColorAsState(targetColorScheme.onSecondary, themeAnimationSpec)
    val animatedSecondaryContainer by animateColorAsState(targetColorScheme.secondaryContainer, themeAnimationSpec)
    val animatedOnSecondaryContainer by animateColorAsState(targetColorScheme.onSecondaryContainer, themeAnimationSpec)
    val animatedTertiary by animateColorAsState(targetColorScheme.tertiary, themeAnimationSpec)
    val animatedOnTertiary by animateColorAsState(targetColorScheme.onTertiary, themeAnimationSpec)
    val animatedTertiaryContainer by animateColorAsState(targetColorScheme.tertiaryContainer, themeAnimationSpec)
    val animatedOnTertiaryContainer by animateColorAsState(targetColorScheme.onTertiaryContainer, themeAnimationSpec)
    val animatedBackground by animateColorAsState(targetColorScheme.background, themeAnimationSpec)
    val animatedOnBackground by animateColorAsState(targetColorScheme.onBackground, themeAnimationSpec)
    val animatedSurface by animateColorAsState(targetColorScheme.surface, themeAnimationSpec)
    val animatedOnSurface by animateColorAsState(targetColorScheme.onSurface, themeAnimationSpec)
    val animatedSurfaceVariant by animateColorAsState(targetColorScheme.surfaceVariant, themeAnimationSpec)
    val animatedOnSurfaceVariant by animateColorAsState(targetColorScheme.onSurfaceVariant, themeAnimationSpec)
    val animatedSurfaceContainer by animateColorAsState(targetColorScheme.surfaceContainer, themeAnimationSpec)
    val animatedSurfaceContainerHigh by animateColorAsState(targetColorScheme.surfaceContainerHigh, themeAnimationSpec)
    val animatedError by animateColorAsState(targetColorScheme.error, themeAnimationSpec)
    val animatedOnError by animateColorAsState(targetColorScheme.onError, themeAnimationSpec)
    val animatedErrorContainer by animateColorAsState(targetColorScheme.errorContainer, themeAnimationSpec)
    val animatedOnErrorContainer by animateColorAsState(targetColorScheme.onErrorContainer, themeAnimationSpec)
    val animatedOutline by animateColorAsState(targetColorScheme.outline, themeAnimationSpec)
    val animatedOutlineVariant by animateColorAsState(targetColorScheme.outlineVariant, themeAnimationSpec)
    val animatedInverseSurface by animateColorAsState(targetColorScheme.inverseSurface, themeAnimationSpec)
    val animatedInverseOnSurface by animateColorAsState(targetColorScheme.inverseOnSurface, themeAnimationSpec)
    val animatedInversePrimary by animateColorAsState(targetColorScheme.inversePrimary, themeAnimationSpec)
    val animatedSurfaceTint by animateColorAsState(targetColorScheme.surfaceTint, themeAnimationSpec)
    val animatedScrim by animateColorAsState(targetColorScheme.scrim, themeAnimationSpec)

    val animatedColorScheme = if (isDark) {
        darkColorScheme(
            primary = animatedPrimary,
            onPrimary = animatedOnPrimary,
            primaryContainer = animatedPrimaryContainer,
            onPrimaryContainer = animatedOnPrimaryContainer,
            secondary = animatedSecondary,
            onSecondary = animatedOnSecondary,
            secondaryContainer = animatedSecondaryContainer,
            onSecondaryContainer = animatedOnSecondaryContainer,
            tertiary = animatedTertiary,
            onTertiary = animatedOnTertiary,
            tertiaryContainer = animatedTertiaryContainer,
            onTertiaryContainer = animatedOnTertiaryContainer,
            background = animatedBackground,
            onBackground = animatedOnBackground,
            surface = animatedSurface,
            onSurface = animatedOnSurface,
            surfaceVariant = animatedSurfaceVariant,
            onSurfaceVariant = animatedOnSurfaceVariant,
            surfaceContainer = animatedSurfaceContainer,
            surfaceContainerHigh = animatedSurfaceContainerHigh,
            error = animatedError,
            onError = animatedOnError,
            errorContainer = animatedErrorContainer,
            onErrorContainer = animatedOnErrorContainer,
            outline = animatedOutline,
            outlineVariant = animatedOutlineVariant,
            inverseSurface = animatedInverseSurface,
            inverseOnSurface = animatedInverseOnSurface,
            inversePrimary = animatedInversePrimary,
            surfaceTint = animatedSurfaceTint,
            scrim = animatedScrim
        )
    } else {
        lightColorScheme(
            primary = animatedPrimary,
            onPrimary = animatedOnPrimary,
            primaryContainer = animatedPrimaryContainer,
            onPrimaryContainer = animatedOnPrimaryContainer,
            secondary = animatedSecondary,
            onSecondary = animatedOnSecondary,
            secondaryContainer = animatedSecondaryContainer,
            onSecondaryContainer = animatedOnSecondaryContainer,
            tertiary = animatedTertiary,
            onTertiary = animatedOnTertiary,
            tertiaryContainer = animatedTertiaryContainer,
            onTertiaryContainer = animatedOnTertiaryContainer,
            background = animatedBackground,
            onBackground = animatedOnBackground,
            surface = animatedSurface,
            onSurface = animatedOnSurface,
            surfaceVariant = animatedSurfaceVariant,
            onSurfaceVariant = animatedOnSurfaceVariant,
            surfaceContainer = animatedSurfaceContainer,
            surfaceContainerHigh = animatedSurfaceContainerHigh,
            error = animatedError,
            onError = animatedOnError,
            errorContainer = animatedErrorContainer,
            onErrorContainer = animatedOnErrorContainer,
            outline = animatedOutline,
            outlineVariant = animatedOutlineVariant,
            inverseSurface = animatedInverseSurface,
            inverseOnSurface = animatedInverseOnSurface,
            inversePrimary = animatedInversePrimary,
            surfaceTint = animatedSurfaceTint,
            scrim = animatedScrim
        )
    }

    MaterialTheme(
        colorScheme = animatedColorScheme,
        shapes = AppShapes,
        content = content
    )
}


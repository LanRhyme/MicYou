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

package com.lanrhyme.micyou.animation

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import kotlin.math.pow

/**
 * 动画默认值常量
 * 统一管理所有动画的默认参数，便于维护和调整
 */
object AnimationDefaults {
    // Pulse 动画默认值
    const val PULSE_MIN_VALUE = 0.8f
    const val PULSE_MAX_VALUE = 1.2f
    const val PULSE_DURATION = 1000

    // Breath 动画默认值
    const val BREATH_MIN_VALUE = 0.95f
    const val BREATH_MAX_VALUE = 1.05f
    const val BREATH_DURATION = 2000

    // Glow 动画默认值
    const val GLOW_MIN_VALUE = 0.3f
    const val GLOW_MAX_VALUE = 1f
    const val GLOW_DURATION = 1500

    // Rotation 动画默认值
    const val ROTATION_DURATION = 20000

    // Wave 动画默认值
    const val WAVE_DURATION = 2000

    // Infinite 动画默认值
    const val INFINITE_DURATION = 1000
}

object EasingFunctions {
    val EaseOutExpo: Easing = Easing { x ->
        if (x == 1f) 1f else 1f - 2f.pow(-10f * x)
    }
    val EaseInOutExpo: Easing = Easing { x ->
        when {
            x == 0f -> 0f
            x == 1f -> 1f
            x < 0.5f -> 2f.pow(20f * x - 10f) / 2f
            else -> (2f - 2f.pow(-20f * x + 10f)) / 2f
        }
    }
    val EaseInOutCubic: Easing = Easing { x ->
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }
}

@Composable
fun rememberInfiniteAnimation(
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int = AnimationDefaults.INFINITE_DURATION,
    easing: Easing = LinearEasing
): Float {
    val transition = rememberInfiniteTransition(label = "InfiniteTransition")
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = easing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "InfiniteFloat"
    ).value
}

@Composable
fun rememberPulseAnimation(
    minValue: Float = AnimationDefaults.PULSE_MIN_VALUE,
    maxValue: Float = AnimationDefaults.PULSE_MAX_VALUE,
    durationMillis: Int = AnimationDefaults.PULSE_DURATION
): Float {
    return rememberInfiniteAnimation(minValue, maxValue, durationMillis, EasingFunctions.EaseInOutCubic)
}

@Composable
fun rememberBreathAnimation(
    minValue: Float = AnimationDefaults.BREATH_MIN_VALUE,
    maxValue: Float = AnimationDefaults.BREATH_MAX_VALUE,
    durationMillis: Int = AnimationDefaults.BREATH_DURATION
): Float {
    return rememberInfiniteAnimation(minValue, maxValue, durationMillis, EasingFunctions.EaseInOutExpo)
}

@Composable
fun rememberGlowAnimation(
    minValue: Float = AnimationDefaults.GLOW_MIN_VALUE,
    maxValue: Float = AnimationDefaults.GLOW_MAX_VALUE,
    durationMillis: Int = AnimationDefaults.GLOW_DURATION
): Float {
    return rememberInfiniteAnimation(minValue, maxValue, durationMillis, EasingFunctions.EaseInOutCubic)
}

@Composable
fun rememberRotationAnimation(
    durationMillis: Int = AnimationDefaults.ROTATION_DURATION
): Float {
    val transition = rememberInfiniteTransition(label = "RotationTransition")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    ).value
}

@Composable
fun rememberWaveAnimation(
    phaseOffset: Float = 0f,
    durationMillis: Int = AnimationDefaults.WAVE_DURATION
): Float {
    val transition = rememberInfiniteTransition(label = "WaveTransition")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave"
    ).value + phaseOffset
}

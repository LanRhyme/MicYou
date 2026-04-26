package com.lanrhyme.micyou

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.animation.rememberBreathAnimation
import com.lanrhyme.micyou.animation.rememberGlowAnimation
import com.lanrhyme.micyou.animation.rememberWaveAnimation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 音频可视化常量配置
 * 预定义所有魔法数字，便于维护和调整
 */
object VisualizerConstants {
    // Volume Ring 配置
    const val VOLUME_RING_BASE_RADIUS_FACTOR = 0.85f
    const val VOLUME_RING_STROKE_WIDTH_DP = 8
    const val VOLUME_RING_TICK_COUNT = 60
    const val VOLUME_RING_MAJOR_TICK_INTERVAL = 5
    const val VOLUME_RING_MAJOR_TICK_LENGTH_DP = 6
    const val VOLUME_RING_MINOR_TICK_LENGTH_DP = 3
    const val VOLUME_RING_INNER_GLOW_RADIUS_FACTOR = 0.6f

    // Ripple 配置
    const val RIPPLE_RING_COUNT_DESKTOP = 3
    const val RIPPLE_RING_COUNT_MOBILE = 4
    const val RIPPLE_BAR_COUNT_DESKTOP = 36
    const val RIPPLE_BAR_COUNT_MOBILE = 48
    const val RIPPLE_BASE_INNER_RADIUS_DESKTOP = 0.55f
    const val RIPPLE_BASE_INNER_RADIUS_MOBILE = 0.45f
    const val RIPPLE_BAR_HEIGHT_FACTOR_DESKTOP = 0.15f
    const val RIPPLE_BAR_HEIGHT_FACTOR_MOBILE = 0.18f
    const val RIPPLE_RING_WIDTH_BASE_DESKTOP = 3f
    const val RIPPLE_RING_WIDTH_BASE_MOBILE = 4f
    const val RIPPLE_GLOW_STEPS = 8

    // Bars 配置
    const val BARS_COUNT = 48
    const val BARS_INNER_RADIUS_FACTOR = 0.35f
    const val BARS_HEIGHT_FACTOR = 0.35f

    // Wave 配置
    const val WAVE_COUNT = 3
    const val WAVE_SEGMENTS = 72
    const val WAVE_BASE_RADIUS_FACTOR = 0.4f
    const val WAVE_RADIUS_INCREMENT = 0.15f
    const val WAVE_AMPLITUDE_FACTOR = 0.08f
    const val WAVE_CENTER_RADIUS_FACTOR = 0.25f

    // Glow 配置
    const val GLOW_LAYERS = 12
    const val GLOW_RAY_COUNT = 8
    const val GLOW_CORE_RADIUS_FACTOR = 0.15f
    const val GLOW_RAY_LENGTH_FACTOR = 0.4f

    // Particles 配置
    const val PARTICLES_COUNT = 36
    const val PARTICLES_BASE_DISTANCE_FACTOR = 0.35f
    const val PARTICLES_CENTER_GLOW_RADIUS_FACTOR = 0.2f
    const val PARTICLES_TRAIL_LENGTH_FACTOR = 0.1f

    // Connecting Animation 配置
    const val CONNECTING_ARC_COUNT = 3
    const val CONNECTING_ARC_ANGLE_OFFSET = 120f
    const val CONNECTING_ARC_SWEEP_BASE_DESKTOP = 60f
    const val CONNECTING_ARC_SWEEP_BASE_MOBILE = 50f

    // 动画时长配置
    const val ANIM_DURATION_BREATH_DESKTOP = 1500
    const val ANIM_DURATION_BREATH_MOBILE = 1800
    const val ANIM_DURATION_WAVE_DESKTOP = 3000
    const val ANIM_DURATION_WAVE_MOBILE = 2500
    const val ANIM_DURATION_GLOW = 2000
    const val ANIM_DURATION_ROTATION_DESKTOP = 2000
    const val ANIM_DURATION_ROTATION_MOBILE = 2500
    const val ANIM_DURATION_PULSE_DESKTOP = 1000
    const val ANIM_DURATION_PULSE_MOBILE = 1200
    const val ANIM_DURATION_LEVEL_UPDATE = 100

    // 缓动函数配置 - 音频级别更新使用线性缓动以保持实时响应
    val EASING_LEVEL_UPDATE = LinearEasing

    // Alpha 值配置
    const val ALPHA_BACKGROUND_RING = 0.15f
    const val ALPHA_END_DOT = 0.9f
    const val ALPHA_TICK_ACTIVE = 0.4f
    const val ALPHA_TICK_INACTIVE = 0.1f
    const val ALPHA_RING_BASE = 0.35f
    const val ALPHA_RING_DECREMENT = 0.07f
    const val ALPHA_BAR_BASE = 0.5f
    const val ALPHA_INNER_GLOW = 0.15f
    const val ALPHA_GLOW_MAX = 0.35f
    const val ALPHA_CORE = 0.6f
    const val ALPHA_RAY = 0.3f
    const val ALPHA_PARTICLE_BASE = 0.3f
}

/**
 * Unified Audio Visualizer Component
 * Supports both Desktop and Mobile platforms with optimized rendering
 */
@Composable
fun AudioVisualizer(
    modifier: Modifier = Modifier,
    audioLevel: Float,
    color: Color,
    style: VisualizerStyle = VisualizerStyle.Ripple,
    isDesktop: Boolean = true
) {
    val safeAudioLevel = remember(audioLevel) { audioLevel.coerceIn(0f, 1f) }
    val breathScale = rememberBreathAnimation(
        minValue = if (isDesktop) 0.98f else 0.97f,
        maxValue = if (isDesktop) 1.02f else 1.03f,
        durationMillis = if (isDesktop) VisualizerConstants.ANIM_DURATION_BREATH_DESKTOP else VisualizerConstants.ANIM_DURATION_BREATH_MOBILE
    )
    val wavePhase = rememberWaveAnimation(
        phaseOffset = 0f,
        durationMillis = if (isDesktop) VisualizerConstants.ANIM_DURATION_WAVE_DESKTOP else VisualizerConstants.ANIM_DURATION_WAVE_MOBILE
    )
    val glowAlpha = rememberGlowAnimation(
        minValue = 0.2f,
        maxValue = 0.5f,
        durationMillis = VisualizerConstants.ANIM_DURATION_GLOW
    )

    when (style) {
        VisualizerStyle.VolumeRing -> VolumeRingVisualizer(
            modifier = modifier,
            audioLevel = safeAudioLevel,
            color = color,
            isDesktop = isDesktop
        )
        VisualizerStyle.Ripple -> RippleVisualizer(
            modifier = modifier,
            audioLevel = safeAudioLevel,
            color = color,
            breathScale = breathScale,
            wavePhase = wavePhase,
            glowAlpha = glowAlpha,
            isDesktop = isDesktop
        )
        VisualizerStyle.Bars -> BarsVisualizer(
            modifier = modifier,
            audioLevel = safeAudioLevel,
            color = color,
            wavePhase = wavePhase,
            isDesktop = isDesktop
        )
        VisualizerStyle.Wave -> WaveVisualizer(
            modifier = modifier,
            audioLevel = safeAudioLevel,
            color = color,
            wavePhase = wavePhase,
            isDesktop = isDesktop
        )
        VisualizerStyle.Glow -> GlowVisualizer(
            modifier = modifier,
            audioLevel = safeAudioLevel,
            color = color,
            glowAlpha = glowAlpha,
            breathScale = breathScale,
            isDesktop = isDesktop
        )
        VisualizerStyle.Particles -> ParticlesVisualizer(
            modifier = modifier,
            audioLevel = safeAudioLevel,
            color = color,
            wavePhase = wavePhase,
            isDesktop = isDesktop
        )
    }
}

/**
 * Volume Ring Visualizer - Circular progress ring with tick marks
 */
@Composable
private fun VolumeRingVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    isDesktop: Boolean
) {
    val animatedLevel by animateFloatAsState(
        targetValue = audioLevel,
        animationSpec = tween(VisualizerConstants.ANIM_DURATION_LEVEL_UPDATE, easing = VisualizerConstants.EASING_LEVEL_UPDATE),
        label = "VolumeLevel"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2 * VisualizerConstants.VOLUME_RING_BASE_RADIUS_FACTOR
        val strokeWidth = VisualizerConstants.VOLUME_RING_STROKE_WIDTH_DP.dp.toPx()

        // Background ring
        drawCircle(
            color = color.copy(alpha = VisualizerConstants.ALPHA_BACKGROUND_RING),
            radius = baseRadius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // Animated arc
        val sweepAngle = 360f * animatedLevel
        val startAngle = -90f

        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
            size = Size(baseRadius * 2, baseRadius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // End point dot
        if (audioLevel > 0.05f) {
            val endAngleRad = Math.toRadians((startAngle + sweepAngle).toDouble()).toFloat()
    val dotX = center.x + baseRadius * cos(endAngleRad)
    val dotY = center.y + baseRadius * sin(endAngleRad)

            drawCircle(
                color = color.copy(alpha = VisualizerConstants.ALPHA_END_DOT),
                radius = strokeWidth * 0.8f,
                center = Offset(dotX, dotY)
            )
        }

        // Tick marks
        for (i in 0 until VisualizerConstants.VOLUME_RING_TICK_COUNT) {
            val tickAngle = -90f + (i.toFloat() / VisualizerConstants.VOLUME_RING_TICK_COUNT) * 360f
            val tickAngleRad = Math.toRadians(tickAngle.toDouble()).toFloat()
    val tickProgress = i.toFloat() / VisualizerConstants.VOLUME_RING_TICK_COUNT

            val innerRadius = baseRadius - strokeWidth * 0.5f
            val outerRadius = baseRadius + strokeWidth * 0.5f

            val tickAlpha = if (tickProgress <= animatedLevel) VisualizerConstants.ALPHA_TICK_ACTIVE else VisualizerConstants.ALPHA_TICK_INACTIVE
            val tickLength = if (i % VisualizerConstants.VOLUME_RING_MAJOR_TICK_INTERVAL == 0)
                VisualizerConstants.VOLUME_RING_MAJOR_TICK_LENGTH_DP.dp.toPx()
                else VisualizerConstants.VOLUME_RING_MINOR_TICK_LENGTH_DP.dp.toPx()
    val startX = center.x + innerRadius * cos(tickAngleRad)
    val startY = center.y + innerRadius * sin(tickAngleRad)
    val endX = center.x + (outerRadius + tickLength) * cos(tickAngleRad)
    val endY = center.y + (outerRadius + tickLength) * sin(tickAngleRad)

            drawLine(
                color = color.copy(alpha = tickAlpha),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = if (i % VisualizerConstants.VOLUME_RING_MAJOR_TICK_INTERVAL == 0) 2.dp.toPx() else 1.dp.toPx()
            )
        }

        // Inner glow
        val glowRadius = baseRadius * VisualizerConstants.VOLUME_RING_INNER_GLOW_RADIUS_FACTOR * animatedLevel
        if (glowRadius > 0) {
            drawCircle(
                color = color.copy(alpha = VisualizerConstants.ALPHA_INNER_GLOW * animatedLevel),
                radius = glowRadius,
                center = center
            )
        }
    }
}

/**
 * Ripple Visualizer - Concentric circles with bars
 */
@Composable
private fun RippleVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    breathScale: Float,
    wavePhase: Float,
    glowAlpha: Float,
    isDesktop: Boolean
) {
    val ringCount = if (isDesktop) VisualizerConstants.RIPPLE_RING_COUNT_DESKTOP else VisualizerConstants.RIPPLE_RING_COUNT_MOBILE
    val barCount = if (isDesktop) VisualizerConstants.RIPPLE_BAR_COUNT_DESKTOP else VisualizerConstants.RIPPLE_BAR_COUNT_MOBILE
    val baseInnerRadius = if (isDesktop) VisualizerConstants.RIPPLE_BASE_INNER_RADIUS_DESKTOP else VisualizerConstants.RIPPLE_BASE_INNER_RADIUS_MOBILE
    val barHeightFactor = if (isDesktop) VisualizerConstants.RIPPLE_BAR_HEIGHT_FACTOR_DESKTOP else VisualizerConstants.RIPPLE_BAR_HEIGHT_FACTOR_MOBILE
    val ringWidthBase = if (isDesktop) VisualizerConstants.RIPPLE_RING_WIDTH_BASE_DESKTOP else VisualizerConstants.RIPPLE_RING_WIDTH_BASE_MOBILE

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2

        // Concentric rings
        for (i in 0..ringCount) {
            val waveRadius = baseRadius * (0.5f + i * 0.15f * audioLevel)
    val alpha = (VisualizerConstants.ALPHA_RING_BASE - i * VisualizerConstants.ALPHA_RING_DECREMENT) * audioLevel

            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = waveRadius,
                center = center,
                style = Stroke(width = (ringWidthBase - i * 0.7f).dp.toPx())
            )
        }

        // Bars
        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f + wavePhase
            val radians = Math.toRadians(angle.toDouble()).toFloat()
    val dynamicLevel = audioLevel * (0.4f + 0.6f * sin(angle * 0.08f + wavePhase * 0.025f))
    val barHeight = baseRadius * barHeightFactor * dynamicLevel

            val innerRadius = baseRadius * baseInnerRadius
            val startX = center.x + innerRadius * cos(radians)
    val startY = center.y + innerRadius * sin(radians)
    val endX = center.x + (innerRadius + barHeight) * cos(radians)
    val endY = center.y + (innerRadius + barHeight) * sin(radians)

            drawLine(
                color = color.copy(alpha = VisualizerConstants.ALPHA_BAR_BASE * audioLevel),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Center glow
        for (i in 0 until VisualizerConstants.RIPPLE_GLOW_STEPS) {
            val progress = i.toFloat() / VisualizerConstants.RIPPLE_GLOW_STEPS
            val glowRadius = baseRadius * 0.3f * (1f + progress * 0.5f)
    val alpha = glowAlpha * (1f - progress) * audioLevel

            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 0.3f)),
                radius = glowRadius,
                center = center
            )
        }
    }
}

/**
 * Bars Visualizer - Vertical bars in a circular arrangement
 */
@Composable
private fun BarsVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    wavePhase: Float,
    isDesktop: Boolean
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2

        for (i in 0 until VisualizerConstants.BARS_COUNT) {
            val angle = (i.toFloat() / VisualizerConstants.BARS_COUNT) * 360f
            val radians = Math.toRadians(angle.toDouble()).toFloat()
    val normalizedAngle = (angle + wavePhase) % 360f
            val dynamicLevel = audioLevel * (0.3f + 0.7f * abs(sin(normalizedAngle * 0.03f + wavePhase * 0.015f)))
    val barHeight = baseRadius * VisualizerConstants.BARS_HEIGHT_FACTOR * dynamicLevel

            val innerRadius = baseRadius * VisualizerConstants.BARS_INNER_RADIUS_FACTOR
            val barWidth = (2.5f * (1f + dynamicLevel * 0.5f)).dp.toPx()

            drawLine(
                color = color.copy(alpha = (0.4f + dynamicLevel * 0.5f).coerceIn(0f, 1f)),
                start = Offset(center.x + innerRadius * cos(radians), center.y + innerRadius * sin(radians)),
                end = Offset(center.x + (innerRadius + barHeight) * cos(radians), center.y + (innerRadius + barHeight) * sin(radians)),
                strokeWidth = barWidth, cap = StrokeCap.Round
            )
        }

        // Inner glow
        val innerGlowRadius = baseRadius * 0.3f
        drawCircle(
            color.copy(alpha = audioLevel * VisualizerConstants.ALPHA_INNER_GLOW),
            innerGlowRadius,
            center
        )
    }
}

/**
 * Wave Visualizer - Multiple concentric wave paths
 */
@Composable
private fun WaveVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    wavePhase: Float,
    isDesktop: Boolean
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2

        for (waveIndex in 0 until VisualizerConstants.WAVE_COUNT) {
            val waveRadius = baseRadius * (VisualizerConstants.WAVE_BASE_RADIUS_FACTOR + waveIndex * VisualizerConstants.WAVE_RADIUS_INCREMENT)
    val waveAmplitude = baseRadius * VisualizerConstants.WAVE_AMPLITUDE_FACTOR * audioLevel * (1f - waveIndex * 0.25f)
    val path = Path()

            for (i in 0..VisualizerConstants.WAVE_SEGMENTS) {
                val angle = (i.toFloat() / VisualizerConstants.WAVE_SEGMENTS) * 360f
                val radians = Math.toRadians(angle.toDouble()).toFloat()
    val waveOffset = waveAmplitude * sin(angle * 0.1f + wavePhase * 0.05f + waveIndex * 1.5f)
    val r = waveRadius + waveOffset

                val x = center.x + r * cos(radians)
    val y = center.y + r * sin(radians)

                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            drawPath(
                path = path,
                color = color.copy(alpha = (0.5f - waveIndex * 0.12f) * audioLevel),
                style = Stroke(width = (3f - waveIndex * 0.5f).dp.toPx())
            )
        }

        // Center circle
        drawCircle(
            color.copy(alpha = audioLevel * VisualizerConstants.ALPHA_INNER_GLOW),
            baseRadius * VisualizerConstants.WAVE_CENTER_RADIUS_FACTOR,
            center
        )
    }
}

/**
 * Glow Visualizer - Radiant glow with rays
 */
@Composable
private fun GlowVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    glowAlpha: Float,
    breathScale: Float,
    isDesktop: Boolean
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2

        // Glow layers
        repeat(VisualizerConstants.GLOW_LAYERS) { i ->
            val progress = i.toFloat() / VisualizerConstants.GLOW_LAYERS
            val glowRadius = baseRadius * (0.2f + progress * 0.6f) * (1f + audioLevel * 0.3f)
    val alpha = (glowAlpha * (1f - progress * 0.8f) * audioLevel).coerceIn(0f, VisualizerConstants.ALPHA_GLOW_MAX)
            drawCircle(color.copy(alpha = alpha), glowRadius, center)
        }

        // Core
        val coreRadius = baseRadius * VisualizerConstants.GLOW_CORE_RADIUS_FACTOR * (1f + audioLevel * 0.5f)
        drawCircle(color.copy(alpha = VisualizerConstants.ALPHA_CORE * audioLevel), coreRadius, center)

        // Rays
        for (i in 0 until VisualizerConstants.GLOW_RAY_COUNT) {
            val angle = (i.toFloat() / VisualizerConstants.GLOW_RAY_COUNT) * 360f
            val radians = Math.toRadians(angle.toDouble()).toFloat()
    val rayLength = baseRadius * VisualizerConstants.GLOW_RAY_LENGTH_FACTOR * audioLevel

            drawLine(
                color = color.copy(alpha = VisualizerConstants.ALPHA_RAY * audioLevel),
                start = center,
                end = Offset(center.x + rayLength * cos(radians), center.y + rayLength * sin(radians)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Particles Visualizer - Floating particles with trails
 */
@Composable
private fun ParticlesVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color,
    wavePhase: Float,
    isDesktop: Boolean
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2

        for (i in 0 until VisualizerConstants.PARTICLES_COUNT) {
            val baseAngle = (i.toFloat() / VisualizerConstants.PARTICLES_COUNT) * 360f
            val angleOffset = sin(wavePhase * 0.02f + i * 0.5f) * 15f
            val angle = baseAngle + angleOffset
            val radians = Math.toRadians(angle.toDouble()).toFloat()
    val distanceVariation = sin(wavePhase * 0.03f + i * 0.3f) * 0.3f
            val baseDistance = baseRadius * (VisualizerConstants.PARTICLES_BASE_DISTANCE_FACTOR + distanceVariation)
    val distance = baseDistance * (0.5f + audioLevel * 0.8f)
    val x = center.x + distance * cos(radians)
    val y = center.y + distance * sin(radians)
    val particleSize = (3f + audioLevel * 4f * abs(sin(wavePhase * 0.02f + i))).dp.toPx()
    val alpha = (VisualizerConstants.ALPHA_PARTICLE_BASE + audioLevel * 0.5f).coerceIn(0f, 1f)

            // Particle
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = particleSize / 2,
                center = Offset(x, y)
            )

            // Trail
            val trailLength = baseRadius * VisualizerConstants.PARTICLES_TRAIL_LENGTH_FACTOR * audioLevel
            drawLine(
                color = color.copy(alpha = alpha * 0.5f),
                start = Offset(x, y),
                end = Offset(
                    x - trailLength * cos(radians),
                    y - trailLength * sin(radians)
                ),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
            )
        }

        // Center glow
        drawCircle(
            color.copy(alpha = audioLevel * VisualizerConstants.ALPHA_INNER_GLOW),
            baseRadius * VisualizerConstants.PARTICLES_CENTER_GLOW_RADIUS_FACTOR,
            center
        )
    }
}

/**
 * Connecting Animation - Spinning arcs
 */
@Composable
fun ConnectingAnimation(
    modifier: Modifier = Modifier,
    color: Color,
    isDesktop: Boolean = true
) {
    val rotation = com.lanrhyme.micyou.animation.rememberRotationAnimation(
        durationMillis = if (isDesktop) VisualizerConstants.ANIM_DURATION_ROTATION_DESKTOP else VisualizerConstants.ANIM_DURATION_ROTATION_MOBILE
    )
    val pulse = com.lanrhyme.micyou.animation.rememberPulseAnimation(
        minValue = if (isDesktop) 0.9f else 0.92f,
        maxValue = if (isDesktop) 1.1f else 1.08f,
        durationMillis = if (isDesktop) VisualizerConstants.ANIM_DURATION_PULSE_DESKTOP else VisualizerConstants.ANIM_DURATION_PULSE_MOBILE
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val radius = min(size.width, size.height) / 2

        for (i in 0..VisualizerConstants.CONNECTING_ARC_COUNT) {
            val arcAngle = rotation + i * VisualizerConstants.CONNECTING_ARC_ANGLE_OFFSET
            val sweepAngle = if (isDesktop) {
                VisualizerConstants.CONNECTING_ARC_SWEEP_BASE_DESKTOP + 20f * sin(rotation * 0.02f)
            } else {
                VisualizerConstants.CONNECTING_ARC_SWEEP_BASE_MOBILE + 30f * sin(rotation * 0.025f)
            }
    val radiusFactor = if (isDesktop) {
                0.5f + i * 0.15f
            } else {
                0.45f + i * 0.18f
            }

            drawArc(
                color = color.copy(alpha = if (isDesktop) 0.4f - i * 0.1f else 0.5f - i * 0.12f),
                startAngle = arcAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius * radiusFactor, center.y - radius * radiusFactor),
                size = Size(radius * 2 * radiusFactor, radius * 2 * radiusFactor),
                style = Stroke(
                    width = if (isDesktop) 3.dp.toPx() else 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }
    }
}

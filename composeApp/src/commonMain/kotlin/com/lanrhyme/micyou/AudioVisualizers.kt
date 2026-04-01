package com.lanrhyme.micyou

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
        durationMillis = if (isDesktop) 1500 else 1800
    )
    val wavePhase = rememberWaveAnimation(
        phaseOffset = 0f,
        durationMillis = if (isDesktop) 3000 else 2500
    )
    val glowAlpha = rememberGlowAnimation(
        minValue = 0.2f,
        maxValue = 0.5f,
        durationMillis = 2000
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
        animationSpec = tween(100, easing = LinearEasing),
        label = "VolumeLevel"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2 * 0.85f
        val strokeWidth = if (isDesktop) 8.dp.toPx() else 8.dp.toPx()

        // Background ring
        drawCircle(
            color = color.copy(alpha = 0.15f),
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
                color = color.copy(alpha = 0.9f),
                radius = strokeWidth * 0.8f,
                center = Offset(dotX, dotY)
            )
        }

        // Tick marks
        val tickCount = 60
        for (i in 0 until tickCount) {
            val tickAngle = -90f + (i.toFloat() / tickCount) * 360f
            val tickAngleRad = Math.toRadians(tickAngle.toDouble()).toFloat()
            val tickProgress = i.toFloat() / tickCount

            val innerRadius = baseRadius - strokeWidth * 0.5f
            val outerRadius = baseRadius + strokeWidth * 0.5f

            val tickAlpha = if (tickProgress <= animatedLevel) 0.4f else 0.1f
            val tickLength = if (i % 5 == 0) 6.dp.toPx() else 3.dp.toPx()

            val startX = center.x + innerRadius * cos(tickAngleRad)
            val startY = center.y + innerRadius * sin(tickAngleRad)
            val endX = center.x + (outerRadius + tickLength) * cos(tickAngleRad)
            val endY = center.y + (outerRadius + tickLength) * sin(tickAngleRad)

            drawLine(
                color = color.copy(alpha = tickAlpha),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = if (i % 5 == 0) 2.dp.toPx() else 1.dp.toPx()
            )
        }

        // Inner glow
        val glowRadius = baseRadius * 0.6f * animatedLevel
        if (glowRadius > 0) {
            drawCircle(
                color = color.copy(alpha = 0.1f * animatedLevel),
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
    val ringCount = if (isDesktop) 3 else 4
    val barCount = if (isDesktop) 36 else 48
    val baseInnerRadius = if (isDesktop) 0.55f else 0.45f
    val barHeightFactor = if (isDesktop) 0.15f else 0.18f
    val ringWidthBase = if (isDesktop) 3f else 4f

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2

        // Concentric rings
        for (i in 0..ringCount) {
            val waveRadius = baseRadius * (0.5f + i * 0.15f * audioLevel)
            val alpha = (0.35f - i * 0.07f) * audioLevel

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
                color = color.copy(alpha = 0.5f * audioLevel),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Center glow
        val glowSteps = 8
        for (i in 0 until glowSteps) {
            val progress = i.toFloat() / glowSteps
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
    val barCount = if (isDesktop) 48 else 48
    val innerRadiusFactor = if (isDesktop) 0.35f else 0.35f
    val barHeightFactor = if (isDesktop) 0.35f else 0.35f

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2

        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f
            val radians = Math.toRadians(angle.toDouble()).toFloat()

            val normalizedAngle = (angle + wavePhase) % 360f
            val dynamicLevel = audioLevel * (0.3f + 0.7f * abs(sin(normalizedAngle * 0.03f + wavePhase * 0.015f)))
            val barHeight = baseRadius * barHeightFactor * dynamicLevel

            val innerRadius = baseRadius * innerRadiusFactor
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
            color.copy(alpha = audioLevel * 0.15f),
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
    val waveCount = 3
    val segments = 72

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2

        for (waveIndex in 0 until waveCount) {
            val waveRadius = baseRadius * (0.4f + waveIndex * 0.15f)
            val waveAmplitude = baseRadius * 0.08f * audioLevel * (1f - waveIndex * 0.25f)

            val path = Path()

            for (i in 0..segments) {
                val angle = (i.toFloat() / segments) * 360f
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
            color.copy(alpha = audioLevel * 0.2f),
            baseRadius * 0.25f,
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
    val glowLayers = 12
    val rayCount = 8

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2

        // Glow layers
        repeat(glowLayers) { i ->
            val progress = i.toFloat() / glowLayers
            val glowRadius = baseRadius * (0.2f + progress * 0.6f) * (1f + audioLevel * 0.3f)
            val alpha = (glowAlpha * (1f - progress * 0.8f) * audioLevel).coerceIn(0f, 0.35f)
            drawCircle(color.copy(alpha = alpha), glowRadius, center)
        }

        // Core
        val coreRadius = baseRadius * 0.15f * (1f + audioLevel * 0.5f)
        drawCircle(color.copy(alpha = 0.6f * audioLevel), coreRadius, center)

        // Rays
        for (i in 0 until rayCount) {
            val angle = (i.toFloat() / rayCount) * 360f
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            val rayLength = baseRadius * 0.4f * audioLevel

            drawLine(
                color = color.copy(alpha = 0.3f * audioLevel),
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
    val particleCount = 36

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2

        for (i in 0 until particleCount) {
            val baseAngle = (i.toFloat() / particleCount) * 360f
            val angleOffset = sin(wavePhase * 0.02f + i * 0.5f) * 15f
            val angle = baseAngle + angleOffset
            val radians = Math.toRadians(angle.toDouble()).toFloat()

            val distanceVariation = sin(wavePhase * 0.03f + i * 0.3f) * 0.3f
            val baseDistance = baseRadius * (0.35f + distanceVariation)
            val distance = baseDistance * (0.5f + audioLevel * 0.8f)

            val x = center.x + distance * cos(radians)
            val y = center.y + distance * sin(radians)

            val particleSize = (3f + audioLevel * 4f * abs(sin(wavePhase * 0.02f + i))).dp.toPx()
            val alpha = (0.3f + audioLevel * 0.5f).coerceIn(0f, 1f)

            // Particle
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = particleSize / 2,
                center = Offset(x, y)
            )

            // Trail
            val trailLength = baseRadius * 0.1f * audioLevel
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
            color.copy(alpha = audioLevel * 0.15f),
            baseRadius * 0.2f,
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
        durationMillis = if (isDesktop) 2000 else 2500
    )
    val pulse = com.lanrhyme.micyou.animation.rememberPulseAnimation(
        minValue = if (isDesktop) 0.9f else 0.92f,
        maxValue = if (isDesktop) 1.1f else 1.08f,
        durationMillis = if (isDesktop) 1000 else 1200
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2

        for (i in 0..2) {
            val arcAngle = rotation + i * 120f
            val sweepAngle = if (isDesktop) {
                60f + 20f * sin(rotation * 0.02f)
            } else {
                50f + 30f * sin(rotation * 0.025f)
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

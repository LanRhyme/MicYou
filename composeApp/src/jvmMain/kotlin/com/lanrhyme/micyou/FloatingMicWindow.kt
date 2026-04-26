package com.lanrhyme.micyou

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun FloatingMicWindow(
    viewModel: MainViewModel,
    window: Window? = null,
    onClose: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val isMuted = state.isMuted
    val isStreaming = state.streamState == StreamState.Streaming

    val contentColor = when {
        isMuted -> MaterialTheme.colorScheme.error
        isStreaming -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val mouseListener = remember(window) {
        object : MouseAdapter() {
            private var dragStartX = 0
            private var dragStartY = 0
            private var windowStartX = 0
            private var windowStartY = 0
            private var isDragging = false
            private var dragThreshold = 5

            override fun mousePressed(e: MouseEvent) {
                dragStartX = e.xOnScreen
                dragStartY = e.yOnScreen
                window?.let {
                    windowStartX = it.location.x
                    windowStartY = it.location.y
                }
                isDragging = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val currentX = e.xOnScreen
                val currentY = e.yOnScreen
                val dx = currentX - dragStartX
                val dy = currentY - dragStartY

                if (!isDragging && (kotlin.math.abs(dx) > dragThreshold || kotlin.math.abs(dy) > dragThreshold)) {
                    isDragging = true
                }

                if (isDragging) {
                    window?.setLocation(windowStartX + dx, windowStartY + dy)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!isDragging) {
                    viewModel.toggleMute()
                }
                isDragging = false
            }
        }
    }

    DisposableEffect(window, mouseListener) {
        window?.addMouseListener(mouseListener)
        window?.addMouseMotionListener(mouseListener)
        onDispose {
            window?.removeMouseListener(mouseListener)
            window?.removeMouseMotionListener(mouseListener)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
    ) {
        if (isMuted) {
            MutedVisualizer(
                modifier = Modifier.fillMaxSize(),
                color = contentColor
            )
        } else if (isStreaming) {
            FloatingAudioVisualizer(
                modifier = Modifier.fillMaxSize(),
                audioLevel = audioLevel,
                color = contentColor
            )
        } else {
            IdleVisualizer(
                modifier = Modifier.fillMaxSize(),
                color = contentColor
            )
        }
    }
}

@Composable
private fun FloatingAudioVisualizer(
    modifier: Modifier,
    audioLevel: Float,
    color: Color
) {
    val safeAudioLevel = audioLevel.coerceIn(0f, 1f)
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing)
        ),
        label = "wavePhase"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2 * 0.85f
        val strokeWidth = 4.dp.toPx()

        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = baseRadius,
            center = center,
            style = Stroke(width = strokeWidth)
        )
    val sweepAngle = 360f * safeAudioLevel
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
            size = Size(baseRadius * 2, baseRadius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    val barCount = 8
        for (i in 0 until barCount) {
            val angle = (i.toFloat() / barCount) * 360f + wavePhase
            val radians = Math.toRadians(angle.toDouble()).toFloat()
    val dynamicLevel = safeAudioLevel * (0.3f + 0.7f * sin(angle * 0.1f + wavePhase * 0.02f))
    val barHeight = baseRadius * 0.12f * dynamicLevel

            val innerRadius = baseRadius * 0.4f
            val startX = center.x + innerRadius * cos(radians)
    val startY = center.y + innerRadius * sin(radians)
    val endX = center.x + (innerRadius + barHeight) * cos(radians)
    val endY = center.y + (innerRadius + barHeight) * sin(radians)

            drawLine(
                color = color.copy(alpha = 0.5f * safeAudioLevel),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        if (safeAudioLevel > 0.05f) {
            val glowRadius = baseRadius * 0.2f * safeAudioLevel
            drawCircle(
                color = color.copy(alpha = 0.1f * safeAudioLevel),
                radius = glowRadius,
                center = center
            )
        }
    }
}

@Composable
private fun MutedVisualizer(
    modifier: Modifier,
    color: Color
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2 * 0.85f
        val strokeWidth = 4.dp.toPx()

        drawCircle(
            color = color.copy(alpha = 0.3f),
            radius = baseRadius,
            center = center,
            style = Stroke(width = strokeWidth)
        )
    val slashLength = baseRadius * 0.7f
        drawLine(
            color = color,
            start = Offset(center.x - slashLength * 0.7f, center.y - slashLength * 0.7f),
            end = Offset(center.x + slashLength * 0.7f, center.y + slashLength * 0.7f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun IdleVisualizer(
    modifier: Modifier,
    color: Color
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2 * 0.85f
        val strokeWidth = 4.dp.toPx()

        drawCircle(
            color = color.copy(alpha = 0.3f),
            radius = baseRadius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        drawCircle(
            color = color.copy(alpha = 0.1f),
            radius = baseRadius * 0.3f,
            center = center
        )
    }
}

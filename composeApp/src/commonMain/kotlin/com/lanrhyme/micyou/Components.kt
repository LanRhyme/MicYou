package com.lanrhyme.micyou

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.animation.EasingFunctions
import kotlinx.coroutines.launch
import kotlin.math.min

@Composable
fun WaterRippleEffect(
    trigger: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    maxScale: Float = 2.0f,
    durationMillis: Int = 600
) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(trigger) {
        if (trigger > 0) {
            alpha.snapTo(1f)
            scale.snapTo(1f)
            launch {
                scale.animateTo(
                    maxScale,
                    animationSpec = tween(durationMillis, easing = EasingFunctions.EaseOutExpo)
                )
            }
            launch {
                alpha.animateTo(
                    0f,
                    animationSpec = tween(durationMillis, easing = EasingFunctions.EaseInOutCubic)
                )
            }
        }
    }

    if (alpha.value > 0f) {
        Box(
            modifier = modifier
                .scale(scale.value)
                .alpha(alpha.value)
                .border(2.dp, color, CircleShape)
        )
    }
}

@Composable
fun AdvancedRippleEffect(
    trigger: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    maxScale: Float = 2.5f,
    durationMillis: Int = 800
) {
    val progress = remember { Animatable(0f) }
    
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.animateTo(
                1f,
                animationSpec = tween(durationMillis, easing = EasingFunctions.EaseOutExpo)
            )
            progress.snapTo(0f)
        }
    }
    
    if (progress.value > 0f) {
        Canvas(modifier = modifier) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = min(size.width, size.height) / 2
            val currentRadius = baseRadius * (1f + (maxScale - 1f) * progress.value)
            val currentAlpha = 1f - progress.value
            
            for (i in 0..2) {
                val ringProgress = (progress.value - i * 0.1f).coerceIn(0f, 1f)
                if (ringProgress > 0f) {
                    val ringRadius = baseRadius * (1f + (maxScale - 1f) * ringProgress)
                    val ringAlpha = currentAlpha * (1f - i * 0.3f)
                    
                    drawCircle(
                        color = color.copy(alpha = ringAlpha.coerceIn(0f, 1f) * 0.5f),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = (3 - i).dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
fun PulsingRingEffect(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    ringCount: Int = 3,
    durationMillis: Int = 2000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulsingRing")
    
    val progressValues = remember { mutableStateListOf<Float>().apply { repeat(ringCount) { add(0f) } } }
    
    for (i in 0 until ringCount) {
        progressValues[i] = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = durationMillis,
                    delayMillis = i * (durationMillis / ringCount),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "Ring$i"
        ).value
    }
    
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        for (i in 0 until ringCount) {
            val progress = progressValues[i]
            val ringRadius = baseRadius * (0.5f + progress * 0.5f)
            val ringAlpha = (1f - progress) * 0.3f
            
            drawCircle(
                color = color.copy(alpha = ringAlpha),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun GlowingBorderEffect(
    modifier: Modifier = Modifier,
    color: Color,
    borderWidth: Dp = 2.dp,
    glowRadius: Dp = 8.dp,
    isAnimating: Boolean = true
) {
    val glowAlpha = if (isAnimating) {
        val transition = rememberInfiniteTransition(label = "GlowBorder")
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = EasingFunctions.EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowAlpha"
        ).value
    } else {
        0.5f
    }
    
    Canvas(modifier = modifier) {
        val glowSteps = 5
        for (i in 0 until glowSteps) {
            val progress = i.toFloat() / glowSteps
            val currentGlowRadius = glowRadius.toPx() * (1f - progress)
            val currentAlpha = glowAlpha * (1f - progress * 0.8f)
            
            drawCircle(
                color = color.copy(alpha = currentAlpha.coerceIn(0f, 1f) * 0.3f),
                radius = (min(size.width, size.height) / 2) + currentGlowRadius,
                center = Offset(size.width / 2, size.height / 2),
                style = Stroke(width = borderWidth.toPx())
            )
        }
    }
}

@Composable
fun OrbitingDotsEffect(
    modifier: Modifier = Modifier,
    color: Color,
    dotCount: Int = 8,
    orbitDuration: Int = 3000
) {
    val rotation = rememberInfiniteTransition(label = "Orbit")
        .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(orbitDuration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "OrbitRotation"
        ).value
    
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val orbitRadius = min(size.width, size.height) / 2 * 0.8f
        
        for (i in 0 until dotCount) {
            val angle = rotation + (i * 360f / dotCount)
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            
            val x = center.x + orbitRadius * kotlin.math.cos(radians)
            val y = center.y + orbitRadius * kotlin.math.sin(radians)
            
            val dotAlpha = 0.3f + 0.7f * (1f - kotlin.math.abs(angle % 360f - 180f) / 180f)
            val dotSize = 3.dp.toPx() + 2.dp.toPx() * (1f - kotlin.math.abs(angle % 360f - 180f) / 180f)
            
            drawCircle(
                color = color.copy(alpha = dotAlpha),
                radius = dotSize,
                center = Offset(x, y)
            )
        }
    }
}

@Composable
fun BreathingGlowEffect(
    modifier: Modifier = Modifier,
    color: Color,
    minAlpha: Float = 0.2f,
    maxAlpha: Float = 0.6f,
    durationMillis: Int = 2000
) {
    val glowAlpha = rememberInfiniteTransition(label = "BreathingGlow")
        .animateFloat(
            initialValue = minAlpha,
            targetValue = maxAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis, easing = EasingFunctions.EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowAlpha"
        ).value
    
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2
        
        val glowSteps = 10
        for (i in 0 until glowSteps) {
            val progress = i.toFloat() / glowSteps
            val currentRadius = baseRadius * (0.3f + progress * 0.7f)
            val currentAlpha = glowAlpha * (1f - progress)
            
            drawCircle(
                color = color.copy(alpha = currentAlpha.coerceIn(0f, 1f)),
                radius = currentRadius,
                center = center
            )
        }
    }
}

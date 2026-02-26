package com.lanrhyme.micyou.animation

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

@Composable
fun AnimatedGlowingCircle(
    modifier: Modifier = Modifier,
    color: Color,
    audioLevel: Float = 0f,
    isAnimating: Boolean = true,
    glowIntensity: Float = 0.5f
) {
    val breathScale = if (isAnimating) rememberBreathAnimation(0.98f, 1.02f, 2000) else 1f
    val glowAlpha = if (isAnimating) rememberGlowAnimation(0.2f, glowIntensity, 1500) else glowIntensity
    
    Canvas(
        modifier = modifier
            .scale(breathScale)
            .clipToBounds()
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val baseRadius = min(size.width, size.height) / 2 * 0.8f
        
        if (isAnimating && audioLevel > 0.01f) {
            drawAudioWaveform(
                center = center,
                radius = baseRadius,
                audioLevel = audioLevel,
                color = color,
                waveCount = 4,
                strokeWidth = 2.dp
            )
        }
        
        drawGlowingCircle(
            center = center,
            radius = baseRadius * 0.3f,
            color = color.copy(alpha = glowAlpha),
            glowRadius = baseRadius * 0.6f,
            glowAlpha = glowAlpha * 0.3f
        )
    }
}

@Composable
fun PulsatingRings(
    modifier: Modifier = Modifier,
    color: Color,
    isAnimating: Boolean = true,
    ringCount: Int = 3,
    maxScale: Float = 2f,
    durationMillis: Int = 2000
) {
    if (!isAnimating) return
    
    val infiniteTransition = rememberInfiniteTransition(label = "RingsTransition")
    
    repeat(ringCount) { index ->
        val progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = durationMillis,
                    delayMillis = index * (durationMillis / ringCount),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "Ring$index"
        )
        
        Canvas(
            modifier = modifier
                .scale(1f + progress * (maxScale - 1f))
                .alpha(1f - progress)
        ) {
            drawCircle(
                color = color,
                radius = min(size.width, size.height) / 2,
                center = Offset(size.width / 2, size.height / 2),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
fun AudioVisualizer(
    modifier: Modifier = Modifier,
    audioLevel: Float,
    color: Color,
    isAnimating: Boolean = true,
    barCount: Int = 32,
    style: VisualizerStyle = VisualizerStyle.Circular
) {
    val safeAudioLevel = audioLevel.coerceIn(0f, 1f)
    val time = if (isAnimating) rememberWaveAnimation() else 0f
    
    Canvas(modifier = modifier.clipToBounds()) {
        when (style) {
            VisualizerStyle.Circular -> drawCircularVisualizer(safeAudioLevel, color, barCount, time)
            VisualizerStyle.Wave -> drawWaveVisualizer(safeAudioLevel, color, time)
            VisualizerStyle.Bars -> drawBarsVisualizer(safeAudioLevel, color, barCount)
        }
    }
}

enum class VisualizerStyle {
    Circular, Wave, Bars
}

private fun DrawScope.drawCircularVisualizer(
    audioLevel: Float,
    color: Color,
    barCount: Int,
    time: Float
) {
    val center = Offset(size.width / 2, size.height / 2)
    val baseRadius = min(size.width, size.height) / 2 * 0.6f
    
    repeat(barCount) { i ->
        val angle = (i.toFloat() / barCount) * 360f + time
        val radians = Math.toRadians(angle.toDouble()).toFloat()
        
        val barHeight = baseRadius * 0.3f * audioLevel * (0.5f + 0.5f * sin(angle * 0.1f + time * 0.02f))
        
        val startX = center.x + baseRadius * cos(radians)
        val startY = center.y + baseRadius * sin(radians)
        val endX = center.x + (baseRadius + barHeight) * cos(radians)
        val endY = center.y + (baseRadius + barHeight) * sin(radians)
        
        val alpha = 0.3f + 0.7f * audioLevel
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawWaveVisualizer(
    audioLevel: Float,
    color: Color,
    time: Float
) {
    val wavePoints = 100
    val amplitude = size.height * 0.15f * audioLevel
    val frequency = 3f
    val pi = PI.toFloat()
    
    val path = Path().apply {
        moveTo(0f, size.height / 2)
        
        for (i in 0..wavePoints) {
            val x = (i.toFloat() / wavePoints) * size.width
            val phase = time * 0.02f
            val y = size.height / 2 + amplitude * sin((x / size.width * frequency * pi * 2 + phase))
            lineTo(x, y)
        }
    }
    
    drawPath(
        path = path,
        color = color.copy(alpha = 0.6f),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawBarsVisualizer(
    audioLevel: Float,
    color: Color,
    barCount: Int
) {
    val barWidth = size.width / barCount
    val spacing = barWidth * 0.2f
    
    repeat(barCount) { i ->
        val barHeight = size.height * audioLevel * (0.3f + 0.7f * Math.random().toFloat())
        val x = i * barWidth + spacing / 2
        
        drawRoundRect(
            color = color.copy(alpha = 0.7f),
            topLeft = Offset(x, size.height - barHeight),
            size = Size(barWidth - spacing, barHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        )
    }
}

@Composable
fun GradientProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Color.Cyan, Color.Blue, Color.Magenta),
    strokeWidth: Dp = 8.dp,
    backgroundColor: Color = Color.Gray.copy(alpha = 0.2f)
) {
    val rotation = rememberRotationAnimation(10000)
    val safeProgress = progress.coerceIn(0f, 1f)
    
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) / 2 - strokeWidth.toPx()
        
        drawCircle(
            color = backgroundColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth.toPx())
        )
        
        drawArc(
            brush = Brush.sweepGradient(
                colors = colors.map { it.copy(alpha = 0.8f) },
                center = center
            ),
            startAngle = rotation,
            sweepAngle = 360f * safeProgress,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun ShimmerEffect(
    modifier: Modifier = Modifier,
    baseColor: Color,
    highlightColor: Color = Color.White.copy(alpha = 0.3f)
) {
    val shimmerProgress = rememberShimmerAnimation()
    
    Canvas(modifier = modifier) {
        val shimmerWidth = size.width * 0.3f
        val startX = -shimmerWidth + shimmerProgress * (size.width + shimmerWidth)
        
        drawRect(color = baseColor)
        
        val gradient = Brush.linearGradient(
            colors = listOf(
                baseColor,
                highlightColor,
                baseColor
            ),
            start = Offset(startX, 0f),
            end = Offset(startX + shimmerWidth, size.height)
        )
        
        drawRect(brush = gradient)
    }
}

@Composable
fun ParticleBackground(
    modifier: Modifier = Modifier,
    particleColor: Color,
    particleCount: Int = 50
) {
    val particleSystem = remember { ParticleSystem(particleCount = particleCount) }
    var particles by remember { mutableStateOf<List<ParticleState>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        particleSystem.initialize(1000f, 1000f)
        while (true) {
            particleSystem.update(1000f, 1000f)
            particles = particleSystem.getParticles()
            delay(16)
        }
    }
    
    Canvas(modifier = modifier) {
        drawParticles(particles, particleColor)
    }
}

@Composable
fun MorphingShape(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color,
    cornerRadiusRange: ClosedFloatingPointRange<Float> = 0f..50f
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val cornerRadius = cornerRadiusRange.start + (cornerRadiusRange.endInclusive - cornerRadiusRange.start) * safeProgress
    
    Canvas(modifier = modifier) {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.dp.toPx())
        )
    }
}

@Composable
fun AnimatedBorder(
    modifier: Modifier = Modifier,
    borderColor: Color,
    borderWidth: Dp = 2.dp,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "BorderTransition")
    val gradientProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GradientProgress"
    )
    
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier.matchParentSize()
        ) {
            val strokeWidth = borderWidth.toPx()
            
            val brush = Brush.sweepGradient(
                colors = listOf(
                    borderColor.copy(alpha = 0.1f),
                    borderColor,
                    borderColor.copy(alpha = 0.1f),
                    borderColor.copy(alpha = 0.1f)
                ),
                center = Offset(size.width / 2, size.height / 2)
            )
            
            drawRoundRect(
                brush = brush,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
                style = Stroke(width = strokeWidth)
            )
        }
        content()
    }
}

@Composable
fun FloatingElement(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val offsetY = rememberInfiniteAnimation(-5f, 5f, 3000, EasingFunctions.EaseInOutCubic)
    
    Box(
        modifier = modifier.graphicsLayer {
            translationY = offsetY
        }
    ) {
        content()
    }
}

@Composable
fun ScaleOnPress(
    pressed: Boolean,
    modifier: Modifier = Modifier,
    scaleDown: Float = 0.95f,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "ScaleOnPress"
    )
    
    Box(
        modifier = modifier.scale(scale)
    ) {
        content()
    }
}

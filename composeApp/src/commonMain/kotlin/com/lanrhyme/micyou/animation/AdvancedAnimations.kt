package com.lanrhyme.micyou.animation

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.*

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
    
    val EaseOutBack: Easing = Easing { x ->
        val c1 = 1.70158f
        val c3 = c1 + 1f
        1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
    }
    
    val EaseInOutBack: Easing = Easing { x ->
        val c1 = 1.70158f
        val c2 = c1 * 1.525f
        when {
            x < 0.5f -> ((2f * x).pow(2) * ((c2 + 1f) * 2f * x - c2)) / 2f
            else -> ((2f * x - 2f).pow(2) * ((c2 + 1f) * (x * 2f - 2f) + c2) + 2f) / 2f
        }
    }
    
    val EaseOutElastic: Easing = Easing { x ->
        val c4 = (2f * PI).toFloat() / 3f
        when {
            x == 0f -> 0f
            x == 1f -> 1f
            else -> 2f.pow(-10f * x) * sin((x * 10f - 0.75f) * c4) + 1f
        }
    }
    
    val EaseOutBounce: Easing = Easing { x ->
        val n1 = 7.5625f
        val d1 = 2.75f
        when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> n1 * (x - 1.5f / d1).pow(2) + 0.75f
            x < 2.5f / d1 -> n1 * (x - 2.25f / d1).pow(2) + 0.9375f
            else -> n1 * (x - 2.625f / d1).pow(2) + 0.984375f
        }
    }
    
    val EaseInOutCubic: Easing = Easing { x ->
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }
    
    val EaseOutQuart: Easing = Easing { x ->
        1f - (1f - x).pow(4)
    }
    
    val EaseOutCirc: Easing = Easing { x ->
        sqrt(1f - (x - 1f).pow(2))
    }
}

object AnimationSpecs {
    fun <T> springBouncy(
        dampingRatio: Float = Spring.DampingRatioMediumBouncy,
        stiffness: Float = Spring.StiffnessLow
    ): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)
    
    fun <T> smoothSlide(durationMillis: Int = 400): TweenSpec<T> = 
        tween(durationMillis, easing = EasingFunctions.EaseOutExpo)
    
    fun <T> smoothScale(durationMillis: Int = 350): TweenSpec<T> = 
        tween(durationMillis, easing = EasingFunctions.EaseOutBack)
    
    fun <T> elastic(durationMillis: Int = 600): TweenSpec<T> = 
        tween(durationMillis, easing = EasingFunctions.EaseOutElastic)
    
    fun <T> bounce(durationMillis: Int = 500): TweenSpec<T> = 
        tween(durationMillis, easing = EasingFunctions.EaseOutBounce)
    
    fun <T> smoothFade(durationMillis: Int = 300): TweenSpec<T> = 
        tween(durationMillis, easing = EasingFunctions.EaseInOutCubic)
    
    fun <T> quickSlide(durationMillis: Int = 250): TweenSpec<T> = 
        tween(durationMillis, easing = EasingFunctions.EaseOutQuart)
}

@Composable
fun animateFloatAsStateWithEasing(
    targetValue: Float,
    animationSpec: AnimationSpec<Float> = AnimationSpecs.smoothSlide(),
    label: String = "FloatAnimation"
): State<Float> {
    return animateFloatAsState(
        targetValue = targetValue,
        animationSpec = animationSpec,
        label = label
    )
}

@Composable
fun rememberInfiniteAnimation(
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int = 1000,
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
    minValue: Float = 0.8f,
    maxValue: Float = 1.2f,
    durationMillis: Int = 1000
): Float {
    return rememberInfiniteAnimation(minValue, maxValue, durationMillis, EasingFunctions.EaseInOutCubic)
}

@Composable
fun rememberBreathAnimation(
    minValue: Float = 0.95f,
    maxValue: Float = 1.05f,
    durationMillis: Int = 2000
): Float {
    return rememberInfiniteAnimation(minValue, maxValue, durationMillis, EasingFunctions.EaseInOutExpo)
}

@Composable
fun rememberGlowAnimation(
    minValue: Float = 0.3f,
    maxValue: Float = 1f,
    durationMillis: Int = 1500
): Float {
    return rememberInfiniteAnimation(minValue, maxValue, durationMillis, EasingFunctions.EaseInOutCubic)
}

@Composable
fun rememberRotationAnimation(
    durationMillis: Int = 20000
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
    durationMillis: Int = 2000
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

@Composable
fun rememberStaggeredAppearAnimation(
    index: Int,
    totalItems: Int,
    visible: Boolean,
    durationMillis: Int = 400
): Float {
    val delay = (index * 50).coerceAtMost(300)
    return animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis, delayMillis = delay, easing = EasingFunctions.EaseOutExpo),
        label = "StaggeredAppear"
    ).value
}

@Composable
fun rememberShimmerAnimation(): Float {
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Shimmer"
    ).value
}

data class ParticleState(
    val x: Float,
    val y: Float,
    val size: Float,
    val alpha: Float,
    val velocityX: Float,
    val velocityY: Float,
    val life: Float
)

class ParticleSystem(
    private val particleCount: Int = 30,
    private val minSize: Float = 2f,
    private val maxSize: Float = 8f,
    private val speed: Float = 1f
) {
    private var particles = mutableListOf<ParticleState>()
    
    fun initialize(width: Float, height: Float) {
        particles = mutableListOf()
        repeat(particleCount) {
            particles.add(createParticle(width, height))
        }
    }
    
    private fun createParticle(width: Float, height: Float): ParticleState {
        return ParticleState(
            x = (0..width.toInt()).random().toFloat(),
            y = (0..height.toInt()).random().toFloat(),
            size = (minSize..maxSize).random(),
            alpha = (0.1f..0.6f).random(),
            velocityX = (-speed..speed).random(),
            velocityY = (-speed..speed).random(),
            life = (0.5f..1f).random()
        )
    }
    
    fun update(width: Float, height: Float, deltaTime: Float = 0.016f) {
        particles = particles.map { p ->
            var newX = p.x + p.velocityX
            var newY = p.y + p.velocityY
            var newLife = p.life - deltaTime * 0.5f
            
            if (newLife <= 0 || newX < 0 || newX > width || newY < 0 || newY > height) {
                createParticle(width, height)
            } else {
                p.copy(x = newX, y = newY, life = newLife, alpha = p.alpha * newLife)
            }
        }.toMutableList()
    }
    
    fun getParticles(): List<ParticleState> = particles
}

fun DrawScope.drawParticles(
    particles: List<ParticleState>,
    color: Color
) {
    particles.forEach { p ->
        drawCircle(
            color = color.copy(alpha = p.alpha),
            radius = p.size,
            center = Offset(p.x, p.y)
        )
    }
}

fun DrawScope.drawGlowingCircle(
    center: Offset,
    radius: Float,
    color: Color,
    glowRadius: Float = radius * 1.5f,
    glowAlpha: Float = 0.3f
) {
    val glowSteps = 10
    repeat(glowSteps) { i ->
        val progress = i.toFloat() / glowSteps
        val currentRadius = radius + (glowRadius - radius) * progress
        val currentAlpha = glowAlpha * (1f - progress)
        drawCircle(
            color = color.copy(alpha = currentAlpha),
            radius = currentRadius,
            center = center
        )
    }
    drawCircle(
        color = color,
        radius = radius,
        center = center
    )
}

fun DrawScope.drawAudioWaveform(
    center: Offset,
    radius: Float,
    audioLevel: Float,
    color: Color,
    waveCount: Int = 3,
    strokeWidth: Dp = 3.dp
) {
    val safeAudioLevel = audioLevel.coerceIn(0f, 1f)
    repeat(waveCount) { index ->
        val waveRadius = radius * (1f + index * 0.15f * safeAudioLevel)
        val alpha = (1f - index * 0.3f) * safeAudioLevel
        drawCircle(
            color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = waveRadius,
            center = center,
            style = Stroke(width = strokeWidth.toPx())
        )
    }
}

fun DrawScope.drawRippleEffect(
    center: Offset,
    progress: Float,
    maxRadius: Float,
    color: Color
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val radius = maxRadius * safeProgress
    val alpha = 1f - safeProgress
    drawCircle(
        color = color.copy(alpha = alpha * 0.5f),
        radius = radius,
        center = center,
        style = Stroke(width = 4.dp.toPx())
    )
}

fun DrawScope.drawGradientArc(
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweepAngle: Float,
    colors: List<Color>,
    strokeWidth: Dp = 8.dp
) {
    if (colors.size < 2) return
    
    drawArc(
        brush = Brush.sweepGradient(
            colors = colors,
            center = center
        ),
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
    )
}

fun ClosedFloatingPointRange<Float>.random(): Float {
    return (Math.random() * (endInclusive - start) + start).toFloat()
}

fun ClosedRange<Int>.random(): Int {
    return (Math.random() * (endInclusive - start) + start).toInt()
}

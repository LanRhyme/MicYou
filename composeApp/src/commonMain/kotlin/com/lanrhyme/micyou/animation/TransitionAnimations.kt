package com.lanrhyme.micyou.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun AnimatedVisibilityWithSlide(
    visible: Boolean,
    modifier: Modifier = Modifier,
    slideDirection: SlideDirection = SlideDirection.Up,
    enterDelay: Int = 0,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val (initialOffsetY, initialOffsetX) = when (slideDirection) {
        SlideDirection.Up -> 100.dp to 0.dp
        SlideDirection.Down -> (-100).dp to 0.dp
        SlideDirection.Left -> 0.dp to 100.dp
        SlideDirection.Right -> 0.dp to (-100).dp
    }
    
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            initialOffsetY = { if (slideDirection == SlideDirection.Up || slideDirection == SlideDirection.Down) initialOffsetY.value.roundToInt() else 0 },
            animationSpec = tween(400, enterDelay, EasingFunctions.EaseOutExpo)
        ) + slideInHorizontally(
            initialOffsetX = { if (slideDirection == SlideDirection.Left || slideDirection == SlideDirection.Right) initialOffsetX.value.roundToInt() else 0 },
            animationSpec = tween(400, enterDelay, EasingFunctions.EaseOutExpo)
        ) + fadeIn(
            animationSpec = tween(300, enterDelay, EasingFunctions.EaseInOutCubic)
        ) + scaleIn(
            initialScale = 0.9f,
            animationSpec = tween(400, enterDelay, EasingFunctions.EaseOutBack)
        ),
        exit = slideOutVertically(
            targetOffsetY = { if (slideDirection == SlideDirection.Up || slideDirection == SlideDirection.Down) initialOffsetY.value.roundToInt() else 0 },
            animationSpec = tween(300, easing = EaseInQuart)
        ) + slideOutHorizontally(
            targetOffsetX = { if (slideDirection == SlideDirection.Left || slideDirection == SlideDirection.Right) initialOffsetX.value.roundToInt() else 0 },
            animationSpec = tween(300, easing = EaseInQuart)
        ) + fadeOut(
            animationSpec = tween(200, easing = EaseInCubic)
        ) + scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(300, easing = EaseInBack)
        ),
        content = content
    )
}

enum class SlideDirection {
    Up, Down, Left, Right
}

@Composable
fun AnimatedScaleVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    scaleFrom: Float = 0.8f,
    enterDelay: Int = 0,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(
            initialScale = scaleFrom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(
            animationSpec = tween(300, enterDelay)
        ),
        exit = scaleOut(
            targetScale = scaleFrom,
            animationSpec = tween(200, easing = EaseInBack)
        ) + fadeOut(
            animationSpec = tween(150)
        ),
        content = content
    )
}

@Composable
fun AnimatedExpandVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    expandFrom: ExpandFrom = ExpandFrom.Bottom,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(
            expandFrom = if (expandFrom == ExpandFrom.Top) Alignment.Top else Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + expandHorizontally(
            expandFrom = if (expandFrom == ExpandFrom.Left) Alignment.Start else Alignment.End,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(
            animationSpec = tween(300)
        ),
        exit = shrinkVertically(
            shrinkTowards = if (expandFrom == ExpandFrom.Top) Alignment.Top else Alignment.Bottom,
            animationSpec = tween(200, easing = EaseInCubic)
        ) + shrinkHorizontally(
            shrinkTowards = if (expandFrom == ExpandFrom.Left) Alignment.Start else Alignment.End,
            animationSpec = tween(200, easing = EaseInCubic)
        ) + fadeOut(
            animationSpec = tween(150)
        ),
        content = content
    )
}

enum class ExpandFrom {
    Top, Bottom, Left, Right
}

@Composable
fun BounceOnAppear(
    visible: Boolean,
    modifier: Modifier = Modifier,
    bounceHeight: Float = 20f,
    content: @Composable () -> Unit
) {
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else bounceHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "BounceOffset"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "BounceAlpha"
    )
    
    Box(
        modifier = modifier
            .offset(y = offsetY.dp)
            .alpha(alpha)
    ) {
        content()
    }
}

@Composable
fun PopInPopOut(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessLow
        ),
        label = "PopScale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200),
        label = "PopAlpha"
    )
    
    Box(
        modifier = modifier
            .scale(scale)
            .alpha(alpha)
            .graphicsLayer {
                transformOrigin = TransformOrigin.Center
            }
    ) {
        content()
    }
}

@Composable
fun ShakeOnTrue(
    trigger: Boolean,
    modifier: Modifier = Modifier,
    shakeIntensity: Float = 5f,
    content: @Composable () -> Unit
) {
    var shouldShake by remember { mutableStateOf(false) }
    val offsetX by animateFloatAsState(
        targetValue = if (shouldShake) shakeIntensity else 0f,
        animationSpec = tween(50, easing = LinearEasing),
        label = "ShakeX"
    )
    
    LaunchedEffect(trigger) {
        if (trigger) {
            repeat(4) { i ->
                shouldShake = i % 2 == 0
                kotlinx.coroutines.delay(50)
            }
            shouldShake = false
        }
    }
    
    Box(
        modifier = modifier.offset(x = offsetX.dp)
    ) {
        content()
    }
}

@Composable
fun PulseOnTrue(
    trigger: Boolean,
    modifier: Modifier = Modifier,
    pulseScale: Float = 1.1f,
    content: @Composable () -> Unit
) {
    var shouldPulse by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (shouldPulse) pulseScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "PulseScale"
    )
    
    LaunchedEffect(trigger) {
        if (trigger) {
            shouldPulse = true
            kotlinx.coroutines.delay(200)
            shouldPulse = false
        }
    }
    
    Box(
        modifier = modifier.scale(scale)
    ) {
        content()
    }
}

@Composable
fun <T> AnimateValueChange(
    value: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    var previousValue by remember { mutableStateOf(value) }
    var hasChanged by remember { mutableStateOf(false) }
    
    LaunchedEffect(value) {
        if (value != previousValue) {
            hasChanged = true
            kotlinx.coroutines.delay(300)
            previousValue = value
            hasChanged = false
        }
    }
    
    val scale by animateFloatAsState(
        targetValue = if (hasChanged) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "ValueChangeScale"
    )
    
    Box(
        modifier = modifier.scale(scale)
    ) {
        content(value)
    }
}

@Composable
fun EntranceAnimation(
    visible: Boolean,
    modifier: Modifier = Modifier,
    entranceType: EntranceType = EntranceType.FadeScale,
    delayMillis: Int = 0,
    content: @Composable () -> Unit
) {
    when (entranceType) {
        EntranceType.FadeScale -> {
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(400, delayMillis, EasingFunctions.EaseInOutCubic),
                label = "EntranceAlpha"
            )
            val scale by animateFloatAsState(
                targetValue = if (visible) 1f else 0.9f,
                animationSpec = tween(500, delayMillis, EasingFunctions.EaseOutBack),
                label = "EntranceScale"
            )
            Box(modifier = modifier.alpha(alpha).scale(scale)) {
                content()
            }
        }
        EntranceType.SlideUp -> {
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(400, delayMillis),
                label = "SlideAlpha"
            )
            val offsetY by animateFloatAsState(
                targetValue = if (visible) 0f else 30f,
                animationSpec = tween(500, delayMillis, EasingFunctions.EaseOutExpo),
                label = "SlideOffset"
            )
            Box(modifier = modifier.alpha(alpha).offset(y = offsetY.dp)) {
                content()
            }
        }
        EntranceType.ScaleBounce -> {
            val scale by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "BounceScale"
            )
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(300, delayMillis),
                label = "BounceAlpha"
            )
            Box(modifier = modifier.scale(scale).alpha(alpha)) {
                content()
            }
        }
    }
}

enum class EntranceType {
    FadeScale, SlideUp, ScaleBounce
}

private val EaseInQuart: Easing = Easing { x ->
    x * x * x * x
}

private val EaseInCubic: Easing = Easing { x ->
    x * x * x
}

private val EaseInBack: Easing = Easing { x ->
    val c1 = 1.70158f
    val c3 = c1 + 1f
    c3 * x * x * x - c1 * x * x
}

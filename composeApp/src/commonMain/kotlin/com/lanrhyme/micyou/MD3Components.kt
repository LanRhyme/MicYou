package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

// ==================== 公共动画工具 ====================

/**
 * 按压缩放动画修饰符
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.95f
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PressScale"
    )
    return this.scale(scale)
}

// ==================== 卡片组件 ====================

enum class MD3CardVariant { Elevated, Filled, Outlined }

/**
 * M3 标准卡片组件
 */
@Composable
fun MD3Card(
    modifier: Modifier = Modifier,
    variant: MD3CardVariant = MD3CardVariant.Filled,
    shape: Shape = MD3Shapes.Card,
    onClick: (() -> Unit)? = null,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false,
    cardOpacity: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerColor = when (variant) {
        MD3CardVariant.Elevated -> MaterialTheme.colorScheme.surfaceContainerLow
        MD3CardVariant.Filled -> MaterialTheme.colorScheme.surfaceContainerHighest
        MD3CardVariant.Outlined -> MaterialTheme.colorScheme.surface
    }

    val elevation = when (variant) {
        MD3CardVariant.Elevated -> CardDefaults.cardElevation(defaultElevation = MD3Elevation.Level2)
        else -> CardDefaults.cardElevation(defaultElevation = MD3Elevation.Level0)
    }

    val border = if (variant == MD3CardVariant.Outlined) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else null

    val clickModifier = onClick?.let {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = it)
    } ?: Modifier

    if (enableHaze && hazeState != null) {
        Box(
            modifier = modifier
                .clip(shape)
                .then(clickModifier)
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = containerColor.copy(alpha = cardOpacity * 0.7f),
                        tints = listOf(HazeTint(color = containerColor.copy(alpha = cardOpacity * 0.7f)))
                    )
                )
        ) {
            Column(content = content)
        }
    } else {
        Card(
            modifier = modifier.then(clickModifier),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor.copy(alpha = cardOpacity)),
            elevation = elevation,
            border = border,
            content = content
        )
    }
}

/**
 * M3 大型卡片组件
 */
@Composable
fun MD3LargeCard(
    modifier: Modifier = Modifier,
    variant: MD3CardVariant = MD3CardVariant.Filled,
    onClick: (() -> Unit)? = null,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false,
    cardOpacity: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) = MD3Card(
    modifier = modifier,
    variant = variant,
    shape = MD3Shapes.CardLarge,
    onClick = onClick,
    hazeState = hazeState,
    enableHaze = enableHaze,
    cardOpacity = cardOpacity,
    content = content
)

// ==================== 列表项组件 ====================

/**
 * M3 列表项组件
 */
@Composable
fun MD3ListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            isHovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "ListItemBackground"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MD3Shapes.ListItem)
            .then(
                onClick?.let { Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = it) }
                    ?: Modifier
            ),
        color = backgroundColor,
        shape = MD3Shapes.ListItem
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MD3Spacing.Large, vertical = MD3Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            if (leading != null) Spacer(Modifier.width(MD3Spacing.ExtraLarge))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

// ==================== 分段按钮组件 ====================

/**
 * M3 分段按钮组
 */
@Composable
fun MD3SegmentedButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MD3Shapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(MD3Spacing.ExtraSmall),
            horizontalArrangement = Arrangement.spacedBy(MD3Spacing.Small),
            content = content
        )
    }
}

/**
 * M3 分段按钮项
 */
@Composable
fun MD3SegmentedButtonItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    label: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(200),
        label = "ContainerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "ContentColor"
    )

    Surface(
        modifier = modifier.pressScale(interactionSource).clip(MD3Shapes.Full),
        color = containerColor,
        shape = MD3Shapes.Full,
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MD3Spacing.Large, vertical = MD3Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD3Spacing.Small)
        ) {
            icon?.let {
                Icon(imageVector = it, contentDescription = null, tint = contentColor, modifier = Modifier.size(MD3Sizes.IconMedium))
            }
            label?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
            }
        }
    }
}

// ==================== 按钮组件 ====================

enum class MD3ButtonVariant { Filled, Tonal, Outlined, Text }

/**
 * M3 统一按钮组件
 */
@Composable
fun MD3Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MD3ButtonVariant = MD3ButtonVariant.Filled,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    text: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = MD3Shapes.Button

    val iconContent: @Composable (() -> Unit)? = icon?.let {
        {
            Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(MD3Sizes.IconMedium))
        }
    }

    val textContent: @Composable (() -> Unit)? = text?.let {
        { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium) }
    }

    when (variant) {
        MD3ButtonVariant.Filled -> Button(
            onClick = onClick,
            modifier = modifier.pressScale(interactionSource),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = MD3Elevation.Level1,
                pressedElevation = MD3Elevation.Level2
            ),
            interactionSource = interactionSource
        ) {
            iconContent?.invoke()
            if (icon != null && text != null) Spacer(Modifier.width(MD3Spacing.Small))
            textContent?.invoke()
        }

        MD3ButtonVariant.Tonal -> {
            val tonalInteractionSource = remember { MutableInteractionSource() }
            androidx.compose.material3.FilledTonalButton(
                onClick = onClick,
                modifier = modifier.pressScale(tonalInteractionSource),
                enabled = enabled,
                shape = shape,
                interactionSource = tonalInteractionSource
            ) {
                iconContent?.invoke()
                if (icon != null && text != null) Spacer(Modifier.width(MD3Spacing.Small))
                textContent?.invoke()
            }
        }

        MD3ButtonVariant.Outlined -> {
            val outlinedInteractionSource = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.pressScale(outlinedInteractionSource),
                enabled = enabled,
                shape = shape,
                interactionSource = outlinedInteractionSource
            ) {
                iconContent?.invoke()
                if (icon != null && text != null) Spacer(Modifier.width(MD3Spacing.Small))
                textContent?.invoke()
            }
        }

        MD3ButtonVariant.Text -> {
            val textInteractionSource = remember { MutableInteractionSource() }
            TextButton(
                onClick = onClick,
                modifier = modifier.pressScale(textInteractionSource),
                enabled = enabled,
                shape = shape,
                interactionSource = textInteractionSource
            ) {
                textContent?.invoke()
            }
        }
    }
}

// 向后兼容的类型别名
@Composable
fun MD3PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    text: String? = null
) = MD3Button(onClick, modifier, MD3ButtonVariant.Filled, enabled, icon, text)

@Composable
fun MD3TonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    text: String? = null
) = MD3Button(onClick, modifier, MD3ButtonVariant.Tonal, enabled, icon, text)

@Composable
fun MD3OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    text: String? = null
) = MD3Button(onClick, modifier, MD3ButtonVariant.Outlined, enabled, icon, text)

@Composable
fun MD3TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String
) = MD3Button(onClick, modifier, MD3ButtonVariant.Text, enabled, text = text)

// ==================== FAB 组件 ====================

enum class MD3FabSize { Small, Normal, Large }

/**
 * M3 统一 FAB 组件
 */
@Composable
fun MD3FloatingActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: MD3FabSize = MD3FabSize.Normal,
    text: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fabSize = when (size) {
        MD3FabSize.Small -> MD3Sizes.FABSizeSmall
        MD3FabSize.Normal -> MD3Sizes.FABSize
        MD3FabSize.Large -> MD3Sizes.FABSizeLarge
    }
    val iconSize = if (size == MD3FabSize.Small) MD3Sizes.IconMedium else MD3Sizes.IconLarge
    val pressedScale = if (size == MD3FabSize.Small) 0.9f else 0.95f

    if (text != null && size != MD3FabSize.Small) {
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier.pressScale(interactionSource, pressedScale),
            icon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(iconSize)) },
            text = { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium) },
            shape = MD3Shapes.FAB,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = MD3Elevation.Level3,
                pressedElevation = MD3Elevation.Level4
            ),
            interactionSource = interactionSource
        )
    } else {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier.size(fabSize).pressScale(interactionSource, pressedScale),
            shape = if (size == MD3FabSize.Small) CircleShape else MD3Shapes.FAB,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = MD3Elevation.Level3,
                pressedElevation = MD3Elevation.Level4
            ),
            interactionSource = interactionSource
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

// 向后兼容的类型别名
@Composable
fun MD3LargeFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    text: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) = MD3FloatingActionButton(onClick, icon, modifier, MD3FabSize.Large, text, containerColor, contentColor)

@Composable
fun MD3SmallFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) = MD3FloatingActionButton(onClick, icon, modifier, MD3FabSize.Small, containerColor = containerColor, contentColor = contentColor)

// ==================== 状态指示器组件 ====================

enum class MD3Status { Idle, Connecting, Active, Error }

/**
 * M3 状态指示器
 */
@Composable
fun MD3StatusIndicator(
    status: MD3Status,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val (containerColor, contentColor) = when (status) {
        MD3Status.Idle -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        MD3Status.Connecting -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        MD3Status.Active -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        MD3Status.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(modifier = modifier, shape = MD3Shapes.Small, color = containerColor) {
        Row(
            modifier = Modifier.padding(horizontal = MD3Spacing.Medium, vertical = MD3Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD3Spacing.Small)
        ) {
            Box(modifier = Modifier.size(8.dp).background(contentColor, CircleShape))
            label?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = contentColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ==================== 信息卡片组件 ====================

enum class MD3InfoType { Info, Success, Warning, Error }

/**
 * M3 信息卡片
 */
@Composable
fun MD3InfoCard(
    message: String,
    modifier: Modifier = Modifier,
    type: MD3InfoType = MD3InfoType.Info
) {
    val (containerColor, contentColor) = when (type) {
        MD3InfoType.Info -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        MD3InfoType.Success -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        MD3InfoType.Warning -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        MD3InfoType.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(modifier = modifier, shape = MD3Shapes.Medium, color = containerColor) {
        Text(
            message,
            modifier = Modifier.padding(MD3Spacing.ExtraLarge),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
    }
}
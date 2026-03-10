package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Material Design 3 卡片变体
 */
enum class MD3CardVariant {
    Elevated,    // 带阴影的凸起卡片
    Filled,      // 填充色卡片
    Outlined     // 带边框的卡片
}

/**
 * M3 标准卡片组件
 * 
 * @param variant 卡片变体：Elevated, Filled, Outlined
 * @param shape 卡片形状，默认使用 M3 标准
 * @param onClick 点击回调，为 null 时卡片不可点击
 * @param hazeState 毛玻璃效果状态
 * @param enableHaze 是否启用毛玻璃效果
 * @param cardOpacity 卡片透明度
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

    val elevation: CardElevation = when (variant) {
        MD3CardVariant.Elevated -> CardDefaults.cardElevation(defaultElevation = MD3Elevation.CardElevated)
        MD3CardVariant.Filled -> CardDefaults.cardElevation(defaultElevation = MD3Elevation.Level0)
        MD3CardVariant.Outlined -> CardDefaults.cardElevation(defaultElevation = MD3Elevation.Level0)
    }

    val border: BorderStroke? = if (variant == MD3CardVariant.Outlined) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else null

    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else Modifier

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
            colors = CardDefaults.cardColors(
                containerColor = containerColor.copy(alpha = cardOpacity)
            ),
            elevation = elevation,
            border = border,
            content = content
        )
    }
}

/**
 * M3 大型卡片组件（用于主要内容区域）
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
) {
    MD3Card(
        modifier = modifier,
        variant = variant,
        shape = MD3Shapes.CardLarge,
        onClick = onClick,
        hazeState = hazeState,
        enableHaze = enableHaze,
        cardOpacity = cardOpacity,
        content = content
    )
}

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
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                } else Modifier
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
                if (supporting != null) {
                    Text(
                        supporting,
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

/**
 * M3 分段按钮组（用于选择器）
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
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "SegmentedButtonScale"
    )

    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
        animationSpec = tween(200),
        label = "SegmentedButtonColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "SegmentedButtonContentColor"
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(MD3Shapes.Full),
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
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(MD3Sizes.IconMedium)
                )
            }
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * M3 主控制按钮（用于主要操作）
 */
@Composable
fun MD3PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    text: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PrimaryButtonScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier.scale(scale),
        enabled = enabled,
        shape = MD3Shapes.Button,
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
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MD3Sizes.IconMedium)
            )
            if (text != null) Spacer(Modifier.width(MD3Spacing.Small))
        }
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * M3 次要按钮（Tonal样式）
 */
@Composable
fun MD3TonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    text: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "TonalButtonScale"
    )

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.scale(scale),
        enabled = enabled,
        shape = MD3Shapes.Button,
        interactionSource = interactionSource
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MD3Sizes.IconMedium)
            )
            if (text != null) Spacer(Modifier.width(MD3Spacing.Small))
        }
        if (text != null) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * M3 轮廓按钮
 */
@Composable
fun MD3OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    text: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "OutlinedButtonScale"
    )

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.scale(scale),
        enabled = enabled,
        shape = MD3Shapes.Button,
        interactionSource = interactionSource
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MD3Sizes.IconMedium)
            )
            if (text != null) Spacer(Modifier.width(MD3Spacing.Small))
        }
        if (text != null) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * M3 文本按钮
 */
@Composable
fun MD3TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "TextButtonScale"
    )

    TextButton(
        onClick = onClick,
        modifier = modifier.scale(scale),
        enabled = enabled,
        shape = MD3Shapes.Button,
        interactionSource = interactionSource
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * M3 大型FAB（用于主要操作）
 */
@Composable
fun MD3LargeFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    text: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "FABScale"
    )

    val elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = MD3Elevation.FAB,
        pressedElevation = MD3Elevation.FABPressed
    )

    if (text != null) {
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier.scale(scale),
            icon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(MD3Sizes.IconLarge)
                )
            },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            },
            shape = MD3Shapes.FABExtended,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = elevation,
            interactionSource = interactionSource
        )
    } else {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier.scale(scale),
            shape = MD3Shapes.FAB,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = elevation,
            interactionSource = interactionSource
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MD3Sizes.IconLarge)
            )
        }
    }
}

/**
 * M3 小型FAB
 */
@Composable
fun MD3SmallFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "SmallFABScale"
    )

    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(MD3Sizes.FABSizeSmall).scale(scale),
        shape = CircleShape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = MD3Elevation.Level2,
            pressedElevation = MD3Elevation.Level3
        ),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(MD3Sizes.IconMedium)
        )
    }
}

/**
 * M3 状态指示器（用于显示连接状态等）
 */
@Composable
fun MD3StatusIndicator(
    status: MD3Status,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val containerColor = when (status) {
        MD3Status.Idle -> MaterialTheme.colorScheme.surfaceVariant
        MD3Status.Connecting -> MaterialTheme.colorScheme.tertiaryContainer
        MD3Status.Active -> MaterialTheme.colorScheme.primaryContainer
        MD3Status.Error -> MaterialTheme.colorScheme.errorContainer
    }

    val contentColor = when (status) {
        MD3Status.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        MD3Status.Connecting -> MaterialTheme.colorScheme.onTertiaryContainer
        MD3Status.Active -> MaterialTheme.colorScheme.onPrimaryContainer
        MD3Status.Error -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier,
        shape = MD3Shapes.Small,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MD3Spacing.Medium, vertical = MD3Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD3Spacing.Small)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(contentColor, CircleShape)
            )
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

enum class MD3Status {
    Idle, Connecting, Active, Error
}

/**
 * M3 信息卡片（用于显示提示信息）
 */
@Composable
fun MD3InfoCard(
    message: String,
    modifier: Modifier = Modifier,
    type: MD3InfoType = MD3InfoType.Info
) {
    val containerColor = when (type) {
        MD3InfoType.Info -> MaterialTheme.colorScheme.primaryContainer
        MD3InfoType.Success -> MaterialTheme.colorScheme.tertiaryContainer
        MD3InfoType.Warning -> MaterialTheme.colorScheme.secondaryContainer
        MD3InfoType.Error -> MaterialTheme.colorScheme.errorContainer
    }

    val contentColor = when (type) {
        MD3InfoType.Info -> MaterialTheme.colorScheme.onPrimaryContainer
        MD3InfoType.Success -> MaterialTheme.colorScheme.onTertiaryContainer
        MD3InfoType.Warning -> MaterialTheme.colorScheme.onSecondaryContainer
        MD3InfoType.Error -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier,
        shape = MD3Shapes.Medium,
        color = containerColor
    ) {
        Text(
            message,
            modifier = Modifier.padding(MD3Spacing.ExtraLarge),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
    }
}

enum class MD3InfoType {
    Info, Success, Warning, Error
}
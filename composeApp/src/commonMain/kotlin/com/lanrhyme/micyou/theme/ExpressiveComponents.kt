package com.lanrhyme.micyou.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Material 3 Expressive 组件样式
 * 更大的圆角、更鲜艳的颜色、更强调的视觉效果
 */

/**
 * Expressive Card - 使用更大的圆角和Expressive配色
 */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = ExpressiveShapes.large,
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            modifier = modifier,
            onClick = onClick,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    }
}

/**
 * Expressive Elevated Card - 带阴影的提升卡片
 */
@Composable
fun ExpressiveElevatedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = ExpressiveShapes.large,
    colors: CardColors = CardDefaults.elevatedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        ElevatedCard(
            modifier = modifier,
            onClick = onClick,
            shape = shape,
            colors = colors,
            elevation = elevation,
            content = content
        )
    } else {
        ElevatedCard(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            content = content
        )
    }
}

/**
 * Expressive Outlined Card - 带边框的轮廓卡片
 */
@Composable
fun ExpressiveOutlinedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = ExpressiveShapes.large,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    elevation: CardElevation = CardDefaults.outlinedCardElevation(),
    border: BorderStroke = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        OutlinedCard(
            modifier = modifier,
            onClick = onClick,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    } else {
        OutlinedCard(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    }
}

/**
 * Expressive Button - 更大的圆角
 */
@Composable
fun ExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ExpressiveShapes.medium,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        content = content
    )
}

@Composable
fun ExpressiveFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ExpressiveShapes.medium,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    content: @Composable RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        content = content
    )
}

@Composable
fun ExpressiveOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ExpressiveShapes.medium,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        content = content
    )
}

@Composable
fun ExpressiveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ExpressiveShapes.small,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        content = content
    )
}

/**
 * Expressive FAB - 超圆角的浮动按钮
 */
@Composable
fun ExpressiveFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = SuperRoundedShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
    content: @Composable () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation,
        content = content
    )
}

@Composable
fun ExpressiveExtendedFloatingActionButton(
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    shape: Shape = SuperRoundedShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        expanded = expanded,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation,
        icon = icon,
        text = text
    )
}

/**
 * Expressive Filter Chip - 更圆角的选择芯片
 */
@Composable
fun ExpressiveFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ExpressiveShapes.small,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
    ),
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon
    )
}

/**
 * Expressive Switch - 更强调的开关样式
 */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.primary,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        uncheckedThumbColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = thumbContent,
        enabled = enabled,
        colors = colors
    )
}

/**
 * Expressive Slider - 更鲜明的滑块
 */
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: SliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        activeTickColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        colors = colors
    )
}

/**
 * Expressive List Group - M3 Expressive 风格的列表组
 * 顶部项有大圆角的顶部，底部项有大圆角的底部，中间项之间有小圆角空隙
 */

/**
 * 顶部圆角形状 - 只有顶部有大圆角
 */
val ExpressiveTopRoundedShape = RoundedCornerShape(
    topStart = 28.dp,
    topEnd = 28.dp,
    bottomStart = 8.dp,
    bottomEnd = 8.dp
)

/**
 * 底部圆角形状 - 只有底部有大圆角
 */
val ExpressiveBottomRoundedShape = RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 8.dp,
    bottomStart = 28.dp,
    bottomEnd = 28.dp
)

/**
 * 中间项形状 - 小圆角
 */
val ExpressiveMiddleRoundedShape = RoundedCornerShape(8.dp)

/**
 * 单项圆角形状 - 顶部和底部都有大圆角（用于只有一个项的情况）
 */
val ExpressiveSingleRoundedShape = RoundedCornerShape(28.dp)

/**
 * Expressive List Group 容器
 * 用于创建 M3 Expressive 风格的设置列表
 *
 * @param modifier Modifier
 * @param itemGap 列表项之间的空隙（默认 4dp）
 * @param containerColor 容器背景色（默认透明，使用 SurfaceContainerLow 作为项背景）
 * @param content 列表项内容，使用 ExpressiveListItem 来构建每个项
 */
@Composable
fun ExpressiveListGroup(
    modifier: Modifier = Modifier,
    itemGap: Dp = 3.dp,
    containerColor: Color = Color.Transparent,
    content: @Composable ExpressiveListGroupScope.() -> Unit
) {
    val scope = ExpressiveListGroupScopeImpl()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor),
        verticalArrangement = Arrangement.spacedBy(itemGap)
    ) {
        scope.content()
    }
}

/**
 * Expressive List Group Scope - 用于跟踪列表项索引
 */
interface ExpressiveListGroupScope {
    @Composable
    fun ExpressiveListItem(
        isFirst: Boolean = false,
        isLast: Boolean = false,
        isSingle: Boolean = false,
        onClick: (() -> Unit)? = null,
        containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
        content: @Composable ColumnScope.() -> Unit
    )
}

private class ExpressiveListGroupScopeImpl : ExpressiveListGroupScope {
    @Composable
    override fun ExpressiveListItem(
        isFirst: Boolean,
        isLast: Boolean,
        isSingle: Boolean,
        onClick: (() -> Unit)?,
        containerColor: Color,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val shape = when {
            isSingle -> ExpressiveSingleRoundedShape
            isFirst -> ExpressiveTopRoundedShape
            isLast -> ExpressiveBottomRoundedShape
            else -> ExpressiveMiddleRoundedShape
        }

        if (onClick != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = containerColor,
                onClick = onClick
            ) {
                Column(content = content)
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = containerColor
            ) {
                Column(content = content)
            }
        }
    }
}

/**
 * Expressive List Item Surface - 基础列表项容器（无内边距，用于嵌套 ListItem）
 */
@Composable
fun ExpressiveListItem(
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isSingle: Boolean = false,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = when {
        isSingle -> ExpressiveSingleRoundedShape
        isFirst -> ExpressiveTopRoundedShape
        isLast -> ExpressiveBottomRoundedShape
        else -> ExpressiveMiddleRoundedShape
    }

    if (enableHaze && hazeState != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = containerColor,
                        tints = listOf(HazeTint(color = containerColor))
                    )
                )
        ) {
            if (onClick != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    onClick = onClick
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    } else {
        if (onClick != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = containerColor,
                onClick = onClick
            ) {
                content()
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = containerColor
            ) {
                content()
            }
        }
    }
}

/**
 * Expressive Settings Box Item - 用于复杂内容的设置项容器（带内边距）
 * @param overlay 可选的覆盖层内容，放置在最外层可覆盖整个卡片（如遮罩）
 */
@Composable
fun ExpressiveSettingsBoxItem(
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isSingle: Boolean = false,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: Dp = 20.dp,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = when {
        isSingle -> ExpressiveSingleRoundedShape
        isFirst -> ExpressiveTopRoundedShape
        isLast -> ExpressiveBottomRoundedShape
        else -> ExpressiveMiddleRoundedShape
    }

    if (enableHaze && hazeState != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = containerColor,
                        tints = listOf(HazeTint(color = containerColor))
                    )
                )
        ) {
            if (onClick != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    onClick = onClick
                ) {
                    Column(
                        modifier = Modifier.padding(contentPadding),
                        content = content
                    )
                }
            } else {
                Column(
                    modifier = Modifier.padding(contentPadding),
                    content = content
                )
            }
            overlay()
        }
    } else {
        if (onClick != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = containerColor,
                onClick = onClick
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(contentPadding),
                        content = content
                    )
                    overlay()
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = containerColor
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(contentPadding),
                        content = content
                    )
                    overlay()
                }
            }
        }
    }
}

/**
 * Expressive Settings Switch Item - 开关设置项
 */
@Composable
fun ExpressiveSettingsSwitchItem(
    headline: String,
    supporting: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isSingle: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
    ExpressiveListItem(
        isFirst = isFirst,
        isLast = isLast,
        isSingle = isSingle,
        onClick = { onCheckedChange(!checked) },
        containerColor = containerColor,
        hazeState = hazeState,
        enableHaze = enableHaze
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (supporting != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null // Handled by row click
            )
        }
    }
}

/**
 * Expressive Settings Dropdown Item - 下拉选择设置项
 */
@Composable
fun <T> ExpressiveSettingsDropdownItem(
    headline: String,
    selected: T,
    options: List<T>,
    labelProvider: (T) -> String,
    onSelect: (T) -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isSingle: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    ExpressiveListItem(
        isFirst = isFirst,
        isLast = isLast,
        isSingle = isSingle,
        onClick = { expanded = true },
        containerColor = containerColor,
        hazeState = hazeState,
        enableHaze = enableHaze
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            )
            Box {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = labelProvider(selected),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(labelProvider(option)) },
                            onClick = { onSelect(option); expanded = false },
                            trailingIcon = {
                                if (selected == option) Icon(Icons.Default.Check, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    }
}
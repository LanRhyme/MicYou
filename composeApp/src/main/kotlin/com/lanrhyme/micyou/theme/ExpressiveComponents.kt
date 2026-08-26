/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.lanrhyme.micyou.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lanrhyme.micyou.ui.compose.haze.HazeState
import com.lanrhyme.micyou.ui.compose.haze.HazeStyle
import com.lanrhyme.micyou.ui.compose.haze.HazeTint
import com.lanrhyme.micyou.ui.compose.haze.hazeEffect

/**
 * Material 3 Expressive 组件样式
 * 更大的圆角、更鲜艳的颜色、更强调的视觉效果
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

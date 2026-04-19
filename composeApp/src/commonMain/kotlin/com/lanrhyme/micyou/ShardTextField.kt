package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A premium, custom styled text field that replaces the standard OutlinedTextField.
 * Features:
 *  - Smooth animated border glow on focus
 *  - Subtle background tint on focus
 *  - Floating label with animated scale and translation
 *  - Rounded corner design consistent with the ShardTheme
 *  - Error state support
 */
@Composable
fun ShardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    isError: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    cornerRadius: Dp = 14.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    // Animated border color
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error
            isFocused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        },
        animationSpec = tween(250)
    )
    
    // Animated border width
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused || isError) 1.8.dp else 1.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    // Animated background
    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)
            isFocused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
        },
        animationSpec = tween(250)
    )
    
    // Label animation
    val hasContent = value.isNotEmpty()
    val labelIsFloating = isFocused || hasContent
    val labelScale by animateFloatAsState(
        targetValue = if (labelIsFloating) 0.78f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    )
    val labelOffsetY by animateFloatAsState(
        targetValue = if (labelIsFloating) -14f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error
            isFocused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = tween(250)
    )
    
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    
    val shape = RoundedCornerShape(cornerRadius)
    
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(bgColor, shape)
                .border(borderWidth, borderColor, shape)
                .padding(start = 14.dp, end = 14.dp, top = if (label != null) 6.dp else 10.dp, bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (label != null) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = labelColor,
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = labelScale
                                    scaleY = labelScale
                                    translationY = labelOffsetY
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                }
                                .padding(vertical = 4.dp)
                        )
                    }

                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (label != null) 14.dp else 0.dp),
                        textStyle = textStyle.copy(color = textColor),
                        singleLine = singleLine,
                        readOnly = readOnly,
                        enabled = enabled,
                        cursorBrush = SolidColor(
                            if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        ),
                        interactionSource = interactionSource,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        visualTransformation = visualTransformation,
                        decorationBox = { innerTextField ->
                            Box {
                                if (value.isEmpty() && placeholder != null && !labelIsFloating) {
                                    Text(
                                        text = placeholder,
                                        style = textStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                if (trailingIcon != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailingIcon()
                }
            }
        }

        if (supportingText != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.padding(start = 14.dp)) {
                supportingText()
            }
        }
    }
}

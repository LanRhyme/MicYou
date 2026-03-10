package com.lanrhyme.micyou

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 设计系统
 * 包含形状、间距、高度、尺寸等设计令牌
 */
object MD3DesignSystem {
    // === 形状 ===
    object Shapes {
        val None = RoundedCornerShape(0.dp)
        val ExtraSmall = RoundedCornerShape(4.dp)
        val Small = RoundedCornerShape(8.dp)
        val Medium = RoundedCornerShape(12.dp)
        val Large = RoundedCornerShape(16.dp)
        val ExtraLarge = RoundedCornerShape(28.dp)
        val Full = RoundedCornerShape(50.dp)

        // 组件专用形状
        val Button = RoundedCornerShape(20.dp)
        val Card = RoundedCornerShape(12.dp)
        val CardLarge = RoundedCornerShape(16.dp)
        val Dialog = RoundedCornerShape(28.dp)
        val FAB = RoundedCornerShape(16.dp)
        val ListItem = RoundedCornerShape(28.dp)
        val SearchBar = RoundedCornerShape(28.dp)
        val BottomSheet = RoundedCornerShape(28.dp)
        val Menu = RoundedCornerShape(12.dp)
    }

    // === 间距 ===
    object Spacing {
        val None = 0.dp
        val ExtraSmall = 2.dp
        val Small = 4.dp
        val Medium = 8.dp
        val Large = 12.dp
        val ExtraLarge = 16.dp
        val XXL = 24.dp
        val XXXL = 32.dp
    }

    // === 高度 ===
    object Elevation {
        val Level0 = 0.dp
        val Level1 = 1.dp
        val Level2 = 3.dp
        val Level3 = 6.dp
        val Level4 = 8.dp
        val Level5 = 12.dp
    }

    // === 尺寸 ===
    object Sizes {
        // 图标
        val IconSmall = 16.dp
        val IconMedium = 20.dp
        val IconLarge = 24.dp
        val IconExtraLarge = 32.dp

        // 按钮
        val ButtonHeight = 40.dp
        val FABSize = 56.dp
        val FABSizeSmall = 40.dp
        val FABSizeLarge = 96.dp
        val IconButtonSize = 48.dp

        // 列表项
        val ListItemHeight = 56.dp
        val ListItemHeightTwoLine = 72.dp
        val ListItemHeightThreeLine = 88.dp

        // 触摸目标
        val TouchTargetMin = 48.dp
    }
}

// 类型别名，保持向后兼容
typealias MD3Shapes = MD3DesignSystem.Shapes
typealias MD3Spacing = MD3DesignSystem.Spacing
typealias MD3Elevation = MD3DesignSystem.Elevation
typealias MD3Sizes = MD3DesignSystem.Sizes

/**
 * 获取 M3 标准形状配置
 */
fun getMD3Shapes(): Shapes = Shapes(
    extraSmall = MD3Shapes.ExtraSmall,
    small = MD3Shapes.Small,
    medium = MD3Shapes.Medium,
    large = MD3Shapes.Large,
    extraLarge = MD3Shapes.ExtraLarge
)
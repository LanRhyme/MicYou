package com.lanrhyme.micyou

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 形状系统
 * 
 * M3 使用更圆润的设计语言，圆角比 M2 更大
 * 形状分为：Extra Small, Small, Medium, Large, Extra Large
 */
object MD3Shapes {
    // M3 标准圆角值
    val CornerNone = 0.dp
    val CornerExtraSmall = 4.dp
    val CornerSmall = 8.dp
    val CornerMedium = 12.dp
    val CornerLarge = 16.dp
    val CornerExtraLarge = 28.dp
    val CornerFull = 50.dp  // 完全圆形/胶囊形

    // 预定义形状
    val None = RoundedCornerShape(CornerNone)
    val ExtraSmall = RoundedCornerShape(CornerExtraSmall)
    val Small = RoundedCornerShape(CornerSmall)
    val Medium = RoundedCornerShape(CornerMedium)
    val Large = RoundedCornerShape(CornerLarge)
    val ExtraLarge = RoundedCornerShape(CornerExtraLarge)
    val Full = RoundedCornerShape(CornerFull)

    // 组件专用形状
    val Button = RoundedCornerShape(20.dp)       // M3 按钮圆角
    val Card = RoundedCornerShape(12.dp)         // M3 卡片圆角
    val CardLarge = RoundedCornerShape(16.dp)    // 大型卡片
    val Dialog = RoundedCornerShape(28.dp)       // M3 对话框圆角
    val TextField = RoundedCornerShape(4.dp)     // M3 输入框圆角
    val Chip = RoundedCornerShape(8.dp)          // M3 芯片圆角
    val FAB = RoundedCornerShape(16.dp)          // M3 FAB 圆角
    val FABExtended = RoundedCornerShape(16.dp)  // M3 扩展FAB圆角
    val ListItem = RoundedCornerShape(28.dp)     // M3 列表项圆角（胶囊形）
    val SearchBar = RoundedCornerShape(28.dp)    // M3 搜索栏圆角
    val TopAppBar = RoundedCornerShape(0.dp)     // 顶部应用栏无圆角
    val BottomSheet = RoundedCornerShape(28.dp)  // 底部抽屉圆角
    val Menu = RoundedCornerShape(12.dp)         // 菜单圆角
    val Snackbar = RoundedCornerShape(8.dp)      // Snackbar 圆角
}

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

/**
 * Material Design 3 间距系统
 */
object MD3Spacing {
    val None = 0.dp
    val ExtraSmall = 2.dp
    val Small = 4.dp
    val Medium = 8.dp
    val Large = 12.dp
    val ExtraLarge = 16.dp
    val XXL = 24.dp
    val XXXL = 32.dp

    // 组件内边距
    val CardPadding = 16.dp
    val ListItemPadding = 16.dp
    val ButtonPaddingHorizontal = 24.dp
    val ButtonPaddingVertical = 8.dp
    val ScreenPadding = 16.dp
    val ScreenPaddingCompact = 12.dp
}

/**
 * Material Design 3 高度系统
 */
object MD3Elevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
    val Level4 = 8.dp
    val Level5 = 12.dp

    // 组件默认高度
    val Card = 1.dp
    val CardElevated = 2.dp
    val Dialog = 6.dp
    val Menu = 2.dp
    val FAB = 3.dp
    val FABPressed = 6.dp
    val NavigationBar = 3.dp
    val TopAppBar = 0.dp  // M3 顶部栏默认无阴影
    val BottomSheet = 1.dp
}

/**
 * Material Design 3 组件尺寸
 */
object MD3Sizes {
    // 图标尺寸
    val IconExtraSmall = 12.dp
    val IconSmall = 16.dp
    val IconMedium = 20.dp
    val IconLarge = 24.dp
    val IconExtraLarge = 32.dp
    val IconXXL = 40.dp
    val IconXXXL = 48.dp

    // 按钮尺寸
    val ButtonHeight = 40.dp
    val ButtonHeightSmall = 32.dp
    val ButtonHeightLarge = 48.dp
    val FABSize = 56.dp
    val FABSizeSmall = 40.dp
    val FABSizeLarge = 96.dp
    val IconButtonSize = 48.dp
    val IconButtonSizeSmall = 40.dp

    // 卡片尺寸
    val CardMinHeight = 48.dp
    val ListItemHeight = 56.dp
    val ListItemHeightTwoLine = 72.dp
    val ListItemHeightThreeLine = 88.dp

    // 输入框
    val TextFieldHeight = 56.dp
    val TextFieldHeightDense = 48.dp

    // 触摸目标
    val TouchTargetMin = 48.dp
}
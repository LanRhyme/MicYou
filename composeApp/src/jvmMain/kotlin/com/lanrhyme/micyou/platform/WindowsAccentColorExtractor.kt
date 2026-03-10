package com.lanrhyme.micyou.platform

import androidx.compose.ui.graphics.Color
import com.lanrhyme.micyou.Logger

/**
 * Windows 系统主题色提取器
 * 参考 Flutter dynamic_color 库实现
 * https://github.com/material-foundation/flutter-packages/tree/main/packages/dynamic_color
 */
object WindowsAccentColorExtractor {

    // Windows 10+ 主题色注册表路径
    private const val ACCENT_REGISTRY_PATH = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Accent"
    private const val ACCENT_COLOR_MENU = "AccentColorMenu"

    // Windows Vista+ DWM 颜色注册表路径
    private const val DWM_REGISTRY_PATH = "HKCU\\Software\\Microsoft\\Windows\\DWM"
    private const val COLORIZATION_COLOR = "ColorizationColor"

    /**
     * 获取 Windows 系统主题色
     * @return 系统主题色，如果无法获取则返回 null
     */
    fun getAccentColor(): Color? {
        return try {
            // Windows 10+: 优先使用 AccentColorMenu
            var color = getAccentColorViaRegistry()

            // 如果失败，尝试 DWM ColorizationColor
            if (color == null) {
                color = getColorizationColor()
            }

            color
        } catch (e: Exception) {
            Logger.e("AccentColorExtractor", "Failed to get Windows accent color", e)
            null
        }
    }

    /**
     * 从 AccentColorMenu 获取主题色 (Windows 10+)
     * 注意：AccentColorMenu 使用 ABGR 格式存储，需要转换为 ARGB
     */
    private fun getAccentColorViaRegistry(): Color? {
        return try {
            val process = ProcessBuilder("reg", "query", ACCENT_REGISTRY_PATH, "/v", ACCENT_COLOR_MENU)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val regex = Regex("$ACCENT_COLOR_MENU\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)", RegexOption.IGNORE_CASE)
            val match = regex.find(output)

            if (match != null) {
                val hexValue = match.groupValues[1]
                val abgr = hexValue.toLong(16)

                // ABGR 转 ARGB: 保留 G 和 A，交换 R 和 B
                // (ABGR & 0xFF00FF00) + ((ABGR & 0xFF) << 16) + ((ABGR & 0xFF0000) >> 16)
                val argb = (abgr and 0xFF00FF00) or
                          ((abgr and 0xFF) shl 16) or
                          ((abgr and 0xFF0000) shr 16)

                val r = ((argb shr 16) and 0xFF).toInt()
                val g = ((argb shr 8) and 0xFF).toInt()
                val b = (argb and 0xFF).toInt()

                Logger.d("AccentColorExtractor", "AccentColorMenu: 0x$hexValue (ABGR) -> 0x${argb.toString(16)} (ARGB) -> RGB($r, $g, $b)")
                Color(r, g, b, 255)
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.d("AccentColorExtractor", "Could not read AccentColorMenu")
            null
        }
    }

    /**
     * 从 DWM ColorizationColor 获取主题色 (Windows Vista+)
     * ColorizationColor 已经是 ARGB 格式
     */
    private fun getColorizationColor(): Color? {
        return try {
            val process = ProcessBuilder("reg", "query", DWM_REGISTRY_PATH, "/v", COLORIZATION_COLOR)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val regex = Regex("$COLORIZATION_COLOR\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)", RegexOption.IGNORE_CASE)
            val match = regex.find(output)

            if (match != null) {
                val hexValue = match.groupValues[1]
                val argb = hexValue.toLong(16)

                // ColorizationColor 已经是 ARGB 格式
                val r = ((argb shr 16) and 0xFF).toInt()
                val g = ((argb shr 8) and 0xFF).toInt()
                val b = (argb and 0xFF).toInt()

                Logger.d("AccentColorExtractor", "ColorizationColor: 0x$hexValue (ARGB) -> RGB($r, $g, $b)")
                Color(r, g, b, 255)
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.d("AccentColorExtractor", "Could not read ColorizationColor")
            null
        }
    }
}
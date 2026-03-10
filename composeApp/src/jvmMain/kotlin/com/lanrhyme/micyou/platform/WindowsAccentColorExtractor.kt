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

    // Pre-compiled regex for registry value extraction
    private val REG_DWORD_REGEX = Regex("REG_DWORD\\s+0x([0-9a-fA-F]+)", RegexOption.IGNORE_CASE)

    /**
     * 获取 Windows 系统主题色
     * @return 系统主题色，如果无法获取则返回 null
     */
    fun getAccentColor(): Color? {
        return try {
            // Windows 10+: 优先使用 AccentColorMenu
            var color = queryRegistryColor(ACCENT_REGISTRY_PATH, ACCENT_COLOR_MENU, convertAbgrToArgb = true)

            // 如果失败，尝试 DWM ColorizationColor
            if (color == null) {
                color = queryRegistryColor(DWM_REGISTRY_PATH, COLORIZATION_COLOR, convertAbgrToArgb = false)
            }

            color
        } catch (e: Exception) {
            Logger.e("AccentColorExtractor", "Failed to get Windows accent color", e)
            null
        }
    }

    /**
     * 从注册表查询颜色值
     * @param registryPath 注册表路径
     * @param valueName 值名称
     * @param convertAbgrToArgb 是否需要从 ABGR 转换为 ARGB
     */
    private fun queryRegistryColor(registryPath: String, valueName: String, convertAbgrToArgb: Boolean): Color? {
        return try {
            val process = ProcessBuilder("reg", "query", registryPath, "/v", valueName)
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText()
                process.waitFor()

                val match = REG_DWORD_REGEX.find(output)
                if (match != null) {
                    val hexValue = match.groupValues[1]
                    val rawValue = hexValue.toLong(16)

                    val argb = if (convertAbgrToArgb) {
                        // ABGR 转 ARGB: 保留 G 和 A，交换 R 和 B
                        abgrToArgb(rawValue)
                    } else {
                        rawValue
                    }

                    val color = argbToColor(argb)
                    val formatNote = if (convertAbgrToArgb) "ABGR" else "ARGB"
                    Logger.d("AccentColorExtractor", "$valueName: 0x$hexValue ($formatNote) -> ${color}")
                    color
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Logger.d("AccentColorExtractor", "Could not read $valueName")
            null
        }
    }

    /**
     * ABGR 转 ARGB
     */
    private fun abgrToArgb(abgr: Long): Long {
        return (abgr and 0xFF00FF00) or
                ((abgr and 0xFF) shl 16) or
                ((abgr and 0xFF0000) shr 16)
    }

    /**
     * 从 ARGB 值创建 Color 对象
     */
    private fun argbToColor(argb: Long): Color {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return Color(r, g, b, 255)
    }
}
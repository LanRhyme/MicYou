package com.lanrhyme.micyou

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private fun toRadians(degrees: Float): Float = degrees * PI.toFloat() / 180f

/**
 * 增强版音频可视化组件
 * 包含 dB 数值显示、峰值指示器和历史图表
 */
@Composable
fun EnhancedAudioVisualizer(
    modifier: Modifier = Modifier,
    levelData: AudioLevelData,
    peakLevel: Float,
    history: List<AudioLevelHistory.AudioLevelSample>,
    color: Color,
    style: VisualizerStyle = VisualizerStyle.VolumeRing,
    showDbMeter: Boolean = true,
    showPeakIndicator: Boolean = true,
    showHistoryChart: Boolean = false,
    isDesktop: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // dB 数值显示
        if (showDbMeter) {
            DbMeterRow(
                levelData = levelData,
                color = color,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 主可视化器 + 峰值指示
        Box(
            modifier = Modifier.size(if (isDesktop) 200.dp else 150.dp)
        ) {
            // 使用现有的可视化样式
            AudioVisualizer(
                modifier = Modifier.fillMaxSize(),
                audioLevel = levelData.rms,
                color = color,
                style = style,
                isDesktop = isDesktop
            )

            // 峰值指示器（外圈）
            if (showPeakIndicator && peakLevel > 0.01f) {
                PeakIndicator(
                    modifier = Modifier.fillMaxSize(),
                    peakLevel = peakLevel,
                    color = color
                )
            }
        }

        // 历史图表
        if (showHistoryChart && history.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LevelHistoryChart(
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
                samples = history,
                color = color
            )
        }
    }
}

/**
 * dB 数值显示行
 */
@Composable
private fun DbMeterRow(
    levelData: AudioLevelData,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // RMS dB
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "RMS",
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.7f)
            )
            Text(
                text = formatDb(levelData.rmsDb),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                ),
                color = color
            )
        }

        // Peak dB
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "Peak",
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.7f)
            )
            Text(
                text = formatDb(levelData.peakDb),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                ),
                color = if (levelData.peakDb > -6f) Color.Red else color
            )
        }
    }
}

/**
 * 峰值指示器 - 在主可视化器周围显示峰值位置
 */
@Composable
private fun PeakIndicator(
    modifier: Modifier,
    peakLevel: Float,
    color: Color
) {
    val animatedPeak by animateFloatAsState(
        targetValue = peakLevel.coerceIn(0f, 1f),
        animationSpec = tween(100),
        label = "PeakLevel"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.92f

        // 峰值角度位置
        val peakAngle = -90f + 360f * animatedPeak
        val peakRad = toRadians(peakAngle)

        val peakX = center.x + radius * cos(peakRad)
        val peakY = center.y + radius * sin(peakRad)

        // 峰值点标记
        drawCircle(
            color = color.copy(alpha = 0.9f),
            radius = 5.dp.toPx(),
            center = Offset(peakX, peakY)
        )

        // 峰值线（从中心到峰值点）
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = center,
            end = Offset(peakX, peakY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/**
 * 电平历史图表 - 显示最近N秒的RMS曲线
 */
@Composable
private fun LevelHistoryChart(
    modifier: Modifier,
    samples: List<AudioLevelHistory.AudioLevelSample>,
    color: Color
) {
    if (samples.size < 2) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        )
        return
    }

    Canvas(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        val width = size.width
        val height = size.height
        val padding = 4.dp.toPx()

        val now = System.currentTimeMillis()
        val timeRange = 10_000L // 10秒

        // 绘制 dB 刻度参考线
        val dbLevels = listOf(0f, -6f, -12f, -18f, -24f)
        for (db in dbLevels) {
            val normalized = 10f.pow(db / 20f)
            val y = padding + (height - 2 * padding) * (1f - normalized)

            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(padding, y),
                end = Offset(width - padding, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // 绘制 RMS 曲线
        val rmsPath = Path()
        for ((index, sample) in samples.withIndex()) {
            val timeOffset = (now - sample.timestamp).toFloat() / timeRange
            val x = padding + (width - 2 * padding) * (1f - timeOffset.coerceIn(0f, 1f))
            val y = padding + (height - 2 * padding) * (1f - sample.rms)

            if (index == 0) {
                rmsPath.moveTo(x, y)
            } else {
                rmsPath.lineTo(x, y)
            }
        }

        drawPath(
            path = rmsPath,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // 绘制峰值曲线（较淡）
        val peakPath = Path()
        for ((index, sample) in samples.withIndex()) {
            val timeOffset = (now - sample.timestamp).toFloat() / timeRange
            val x = padding + (width - 2 * padding) * (1f - timeOffset.coerceIn(0f, 1f))
            val y = padding + (height - 2 * padding) * (1f - sample.peak)

            if (index == 0) {
                peakPath.moveTo(x, y)
            } else {
                peakPath.lineTo(x, y)
            }
        }

        drawPath(
            path = peakPath,
            color = color.copy(alpha = 0.4f),
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * 格式化 dB 值显示
 */
private fun formatDb(db: Float): String {
    return if (db <= AudioLevelData.NEGATIVE_INFINITY_DB) {
        "-∞ dB"
    } else {
        "${db.toInt()} dB"
    }
}

/**
 * 简化版 dB 仪表 - 仅显示数值
 */
@Composable
fun DbMeter(
    modifier: Modifier = Modifier,
    levelData: AudioLevelData,
    color: Color
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // RMS 显示
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "RMS",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.6f)
            )
            Text(
                text = formatDb(levelData.rmsDb),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = color
            )
        }

        // Peak 显示
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Peak",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.6f)
            )
            Text(
                text = formatDb(levelData.peakDb),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (levelData.peakDb > -6f) Color.Red else color
            )
        }
    }
}

/**
 * 音频指标显示 - 比特率和延迟
 */
@Composable
fun AudioMetricsDisplay(
    modifier: Modifier = Modifier,
    metrics: AudioMetrics?,
    color: Color
) {
    if (metrics == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 比特率显示
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Bitrate",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.6f)
            )
            Text(
                text = "${metrics.bitrateKbps()} kbps",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = color
            )
        }

        // 延迟显示
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Latency",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.6f)
            )
            Text(
                text = "${metrics.latencyMs} ms",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = if (metrics.latencyMs > 200) Color(0xFFFF9800) else color
            )
        }
    }
}
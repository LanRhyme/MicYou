package com.lanrhyme.micyou.ui.visualizer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import com.lanrhyme.micyou.ui.visualizer.SpectrumAnalyzerView

@Composable
fun SpectrumAnalyzerView(
    rawSpectrumFlow: Flow<FloatArray>,
    processedSpectrumFlow: Flow<FloatArray>,
    modifier: Modifier = Modifier,
    barCount: Int = 64,
    showLabels: Boolean = true
) {
    val rawSpectrum by rawSpectrumFlow.collectAsState(initial = FloatArray(0))
    val processedSpectrum by processedSpectrumFlow.collectAsState(initial = FloatArray(0))

    Column(modifier = modifier) {
        if (showLabels) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                    Text("原始 (Raw)", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    Text("处理后 (Processed)", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            SpectrumCanvas(
                spectrum = rawSpectrum,
                processedSpectrum = processedSpectrum,
                barCount = barCount,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun SpectrumCanvas(
    spectrum: FloatArray,
    processedSpectrum: FloatArray,
    barCount: Int,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val rawColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    
    Canvas(modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        val width = size.width
        val height = size.height
        val barWidth = width / barCount
        val gap = 2.dp.toPx()
        val effectiveBarWidth = barWidth - gap

        // 辅助函数：将 FFT 幅值转换为平滑的 UI 高度
        fun getBarHeight(data: FloatArray, index: Int, totalBars: Int): Float {
            if (data.isEmpty()) return 0f
            
            // 将频率范围划分为指数级增长的 bin（低频更细，高频更粗，符合听觉）
            val startIdx = (index.toFloat() / totalBars).pow(1.5f) * (data.size - 1)
            val endIdx = ((index + 1).toFloat() / totalBars).pow(1.5f) * (data.size - 1)
            
            var maxVal = 0f
            for (i in startIdx.toInt()..endIdx.toInt().coerceAtMost(data.size - 1)) {
                maxVal = max(maxVal, data[i])
            }
            
            // 转换为 dB 刻度 (近似)
            val db = if (maxVal > 0) 20f * log10(maxVal + 1e-6f) else -100f
            // 映射到 0..1 范围 (-60dB 到 0dB)
            val normalized = ((db + 60f) / 60f).coerceIn(0f, 1f)
            return normalized * height
        }

        // 绘制原始频谱 (背景)
        for (i in 0 until barCount) {
            val h = getBarHeight(spectrum, i, barCount)
            if (h > 0) {
                drawRoundRect(
                    color = rawColor,
                    topLeft = Offset(i * barWidth + gap / 2, height - h),
                    size = Size(effectiveBarWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }

        // 绘制处理后频谱 (前景)
        for (i in 0 until barCount) {
            val h = getBarHeight(processedSpectrum, i, barCount)
            if (h > 0) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(primaryColor.copy(alpha = 0.7f), primaryColor)
                    ),
                    topLeft = Offset(i * barWidth + gap / 2, height - h),
                    size = Size(effectiveBarWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}
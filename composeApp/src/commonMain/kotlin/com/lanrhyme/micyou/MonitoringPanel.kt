package com.lanrhyme.micyou

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.monitoringBitrate
import micyou.composeapp.generated.resources.monitoringBuffer
import micyou.composeapp.generated.resources.monitoringHint
import micyou.composeapp.generated.resources.monitoringJitter
import micyou.composeapp.generated.resources.monitoringLoss
import micyou.composeapp.generated.resources.monitoringRtt
import micyou.composeapp.generated.resources.monitoringSampleRate
import micyou.composeapp.generated.resources.monitoringSpecs
import micyou.composeapp.generated.resources.monitoringTitle
import micyou.composeapp.generated.resources.monitoringTotalLatency
import micyou.composeapp.generated.resources.monitoringTrend
import micyou.composeapp.generated.resources.monitoringWaveform
import org.jetbrains.compose.resources.stringResource

@Composable
fun MonitoringPanel(
    metrics: AudioMetrics?,
    history: List<AudioMetrics>,
    audioLevel: Float,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
    // 持续滚动的波形数据
    val waveformSamples = remember { mutableStateListOf<Float>() }
    val maxSamples = 60 // 约 3 秒的数据（以 50ms 间隔计算）
    
    LaunchedEffect(audioLevel, isRunning) {
        if (isRunning) {
            waveformSamples.add(audioLevel)
            if (waveformSamples.size > maxSamples) {
                waveformSamples.removeAt(0)
            }
        } else {
            waveformSamples.clear()
        }
    }

    HazeSurface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity),
        hazeColor = MaterialTheme.colorScheme.surfaceBright.copy(alpha = cardOpacity * 0.7f),
        modifier = modifier.fillMaxHeight(),
        hazeState = hazeState,
        enabled = enableHaze
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Analytics,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.monitoringTitle),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (isRunning && metrics != null) {
                    StatusIndicator(metrics)
                }
            }

            // Real-time Metrics (Simplified, unified text color)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricRow(
                    label = stringResource(Res.string.monitoringRtt),
                    value = if (isRunning) "${metrics?.networkLatencyMs ?: 0}" else "--",
                    unit = "ms",
                    color = MaterialTheme.colorScheme.onSurface // Unified color
                )
                MetricRow(
                    label = stringResource(Res.string.monitoringJitter),
                    value = if (isRunning) String.format("%.1f", metrics?.jitterMs ?: 0.0) else "--",
                    unit = "ms",
                    color = MaterialTheme.colorScheme.onSurface // Unified color
                )
                MetricRow(
                    label = stringResource(Res.string.monitoringLoss),
                    value = if (isRunning) String.format("%.1f", metrics?.packetLossRate ?: 0.0) else "--",
                    unit = "%",
                    color = MaterialTheme.colorScheme.onSurface // Unified color
                )
            }

            // Latency Trend (Minimalist)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelWithIcon(Icons.Rounded.Timeline, stringResource(Res.string.monitoringTrend))
                LatencyTrendChart(
                    history = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )
            }

            // Waveform (Continuous Scrolling)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelWithIcon(Icons.Rounded.GraphicEq, stringResource(Res.string.monitoringWaveform))
                ContinuousWaveform(
                    samples = waveformSamples,
                    isRunning = isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f))
                )
            }

            // Audio Specs (Subtle Card)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelWithIcon(Icons.Rounded.SettingsInputComponent, stringResource(Res.string.monitoringSpecs))
                AudioSpecsContent(metrics, isRunning)
            }
            
            Text(
                stringResource(Res.string.monitoringHint),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun StatusIndicator(metrics: AudioMetrics) {
    val color = when {
        metrics.packetLossRate > 5.0 || metrics.latencyMs > 500 -> Color(0xFFF44336)
        metrics.packetLossRate > 1.0 || metrics.latencyMs > 200 -> Color(0xFFFF9800)
        else -> Color(0xFF4CAF50)
    }
    
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color)
    )
}

@Composable
private fun MetricRow(label: String, value: String, unit: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            if (value != "--") {
                Spacer(Modifier.width(2.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun LabelWithIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AudioSpecsContent(metrics: AudioMetrics?, isRunning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SpecItem(stringResource(Res.string.monitoringSampleRate), if (isRunning) "${metrics?.sampleRate ?: 0} Hz" else "--")
        SpecItem(stringResource(Res.string.monitoringBitrate), if (isRunning) "${(metrics?.bitrate ?: 0) / 1000} kbps" else "--")
        SpecItem(stringResource(Res.string.monitoringTotalLatency), if (isRunning) "${metrics?.latencyMs ?: 0} ms" else "--")
        SpecItem(stringResource(Res.string.monitoringBuffer), if (isRunning) "${metrics?.bufferDurationMs ?: 0} ms" else "--")
    }
}

@Composable
private fun SpecItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LatencyTrendChart(
    history: List<AudioMetrics>,
    modifier: Modifier = Modifier
) {
    val colorPrimary = MaterialTheme.colorScheme.primary
    val colorNetwork = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)

    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas

        val width = size.width
        val height = size.height
        val maxLatency = history.maxOf { it.latencyMs }.coerceAtLeast(100L).toFloat() * 1.4f
        
        val stepX = width / (history.size - 1)

        // Path for Network Latency (RTT) - Area fill
        val networkPath = Path().apply {
            moveTo(0f, height)
            history.forEachIndexed { index, metrics ->
                val x = index * stepX
                val y = height - (metrics.networkLatencyMs.toFloat() / maxLatency * height)
                lineTo(x, y)
            }
            lineTo(width, height)
            close()
        }

        // Path for Total Latency - Line
        val totalPath = Path().apply {
            history.forEachIndexed { index, metrics ->
                val x = index * stepX
                val y = height - (metrics.latencyMs.toFloat() / maxLatency * height)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawPath(
            networkPath,
            brush = Brush.verticalGradient(
                colors = listOf(colorNetwork.copy(alpha = 0.3f), Color.Transparent)
            )
        )
        drawPath(totalPath, colorPrimary, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ContinuousWaveform(
    samples: List<Float>,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        if (!isRunning || samples.isEmpty()) {
            drawLine(
                color = color.copy(alpha = 0.2f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1.dp.toPx()
            )
            return@Canvas
        }

        val path = Path()
        val stepX = width / (samples.size.coerceAtLeast(2) - 1)

        samples.forEachIndexed { index, level ->
            val x = index * stepX
            val amplitude = (height * 0.4f) * level.coerceIn(0f, 1f)
            
            // Draw symmetric waveform bars
            drawLine(
                color = color.copy(alpha = 0.8f),
                start = Offset(x, centerY - amplitude - 1.dp.toPx()),
                end = Offset(x, centerY + amplitude + 1.dp.toPx()),
                strokeWidth = (stepX * 0.6f).coerceAtLeast(1.dp.toPx()),
                cap = StrokeCap.Round
            )
        }
    }
}

private fun getLatencyColor(ms: Long): Color = when {
    ms > 300 -> Color(0xFFF44336)
    ms > 100 -> Color(0xFFFF9800)
    else -> Color(0xFF4CAF50)
}

private fun getJitterColor(ms: Double): Color = when {
    ms > 50.0 -> Color(0xFFF44336)
    ms > 20.0 -> Color(0xFFFF9800)
    else -> Color(0xFF4CAF50)
}

private fun getLossColor(rate: Double): Color = when {
    rate > 5.0 -> Color(0xFFF44336)
    rate > 1.0 -> Color(0xFFFF9800)
    else -> Color(0xFF4CAF50)
}

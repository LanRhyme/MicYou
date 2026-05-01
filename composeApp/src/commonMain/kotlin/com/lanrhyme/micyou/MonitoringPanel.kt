package com.lanrhyme.micyou

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import micyou.composeapp.generated.resources.monitoringStatusGood
import micyou.composeapp.generated.resources.monitoringStatusNormal
import micyou.composeapp.generated.resources.monitoringStatusPoor
import micyou.composeapp.generated.resources.monitoringTitle
import micyou.composeapp.generated.resources.monitoringTotalLatency
import micyou.composeapp.generated.resources.monitoringTrend
import micyou.composeapp.generated.resources.monitoringWaveform
import org.jetbrains.compose.resources.stringResource

@Composable
fun MonitoringPanel(
    metrics: AudioMetrics?,
    history: List<AudioMetrics>,
    modifier: Modifier = Modifier,
    cardOpacity: Float = 1f,
    hazeState: HazeState? = null,
    enableHaze: Boolean = false
) {
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
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.monitoringTitle),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (metrics != null) {
                    StatusBadge(metrics)
                }
            }

            // Core metrics
            MetricsGrid(metrics)

            // Latency Trend
            Text(
                stringResource(Res.string.monitoringTrend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            LatencyTrendChart(
                history = history,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            )

            // Audio Waveform
            Text(
                stringResource(Res.string.monitoringWaveform),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            SimpleWaveform(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            )

            // Audio Specs
            AudioSpecsCard(metrics)
            
            Text(
                stringResource(Res.string.monitoringHint),
                style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun StatusBadge(metrics: AudioMetrics) {
    val (status, color) = when {
        metrics.packetLossRate > 5.0 || metrics.latencyMs > 500 -> stringResource(Res.string.monitoringStatusPoor) to Color(0xFFF44336)
        metrics.packetLossRate > 1.0 || metrics.latencyMs > 200 -> stringResource(Res.string.monitoringStatusNormal) to Color(0xFFFF9800)
        else -> stringResource(Res.string.monitoringStatusGood) to Color(0xFF4CAF50)
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            status,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MetricsGrid(metrics: AudioMetrics?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricItem(
                label = stringResource(Res.string.monitoringRtt),
                value = "${metrics?.networkLatencyMs ?: 0}",
                unit = "ms",
                modifier = Modifier.weight(1f),
                color = getLatencyColor(metrics?.networkLatencyMs ?: 0)
            )
            MetricItem(
                label = stringResource(Res.string.monitoringJitter),
                value = String.format("%.1f", metrics?.jitterMs ?: 0.0),
                unit = "ms",
                modifier = Modifier.weight(1f),
                color = getJitterColor(metrics?.jitterMs ?: 0.0)
            )
        }
        MetricItem(
            label = stringResource(Res.string.monitoringLoss),
            value = String.format("%.1f", metrics?.packetLossRate ?: 0.0),
            unit = "%",
            modifier = Modifier.fillMaxWidth(),
            color = getLossColor(metrics?.packetLossRate ?: 0.0)
        )
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    unit,
                    style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp),
                    modifier = Modifier.padding(bottom = 2.dp, start = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AudioSpecsCard(metrics: AudioMetrics?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.monitoringSpecs), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            
            Row(modifier = Modifier.fillMaxWidth()) {
                SpecDetail(stringResource(Res.string.monitoringSampleRate), "${metrics?.sampleRate ?: 0} Hz", Modifier.weight(1f))
                SpecDetail(stringResource(Res.string.monitoringBitrate), "${(metrics?.bitrate ?: 0) / 1000} kbps", Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Row(modifier = Modifier.fillMaxWidth()) {
                SpecDetail(stringResource(Res.string.monitoringTotalLatency), "${metrics?.latencyMs ?: 0} ms", Modifier.weight(1f))
                SpecDetail(stringResource(Res.string.monitoringBuffer), "${metrics?.bufferDurationMs ?: 0} ms", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpecDetail(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LatencyTrendChart(
    history: List<AudioMetrics>,
    modifier: Modifier = Modifier
) {
    val colorPrimary = MaterialTheme.colorScheme.primary
    val colorNetwork = Color(0xFF2196F3) // Blue for RTT

    Canvas(modifier = modifier.padding(8.dp)) {
        if (history.size < 2) return@Canvas

        val width = size.width
        val height = size.height
        val maxLatency = history.maxOf { it.latencyMs }.coerceAtLeast(100L).toFloat() * 1.2f
        
        val stepX = width / (history.size - 1)

        // Draw grid lines
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = height - (i * height / gridLines)
            drawLine(
                color = Color.LightGray.copy(alpha = 0.2f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Path for Network Latency (RTT)
        val networkPath = Path().apply {
            history.forEachIndexed { index, metrics ->
                val x = index * stepX
                val y = height - (metrics.networkLatencyMs.toFloat() / maxLatency * height)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        // Path for Total Latency
        val totalPath = Path().apply {
            history.forEachIndexed { index, metrics ->
                val x = index * stepX
                val y = height - (metrics.latencyMs.toFloat() / maxLatency * height)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawPath(networkPath, colorNetwork, style = Stroke(width = 2.dp.toPx()))
        drawPath(totalPath, colorPrimary, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun SimpleWaveform(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "WaveformAnimation")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * kotlin.math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing)
        ),
        label = "Phase"
    )

    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        val path = Path()

        for (x in 0..width.toInt() step 2) {
            val normalizedX = x.toFloat() / width
            val angle = normalizedX * 4f * kotlin.math.PI.toFloat() + phase
            val amplitude = kotlin.math.sin(angle) * (height / 3) * (1f - kotlin.math.abs(0.5f - normalizedX) * 2f)
            
            if (x == 0) path.moveTo(x.toFloat(), centerY + amplitude)
            else path.lineTo(x.toFloat(), centerY + amplitude)
        }

        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
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

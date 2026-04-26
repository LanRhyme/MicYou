package com.lanrhyme.micyou

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import micyou.composeapp.generated.resources.*
import micyou.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

@Serializable
data class GitHubContributor(
    @SerialName("login")
    val login: String,
    @SerialName("avatar_url")
    val avatarUrl: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("contributions")
    val contributions: Int
)

/**
 * Each contributor becomes a "bubble" that floats with a unique phase/amplitude.
 */
private data class ContributorBubble(
    val contributor: GitHubContributor,
    val size: Float,         // dp size
    val floatPhase: Float,   // random phase offset for floating animation
    val floatAmpX: Float,    // horizontal float amplitude (dp)
    val floatAmpY: Float,    // vertical float amplitude (dp)
    val floatSpeedX: Float,  // horizontal oscillation speed factor
    val floatSpeedY: Float,  // vertical oscillation speed factor
) {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
}

@Composable
fun ContributorsDialog(onDismiss: () -> Unit) {    var bubbles by remember { mutableStateOf<List<ContributorBubble>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        try {
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
                }
            }
    val response = client.get("https://api.github.com/repos/LanRhyme/MicYou/contributors") {
                header(HttpHeaders.UserAgent, "MicYouApp")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            if (response.status.isSuccess()) {
                val list = response.body<List<GitHubContributor>>()
                bubbles = list.map { c ->
                    val sz = 70f + min(c.contributions.toFloat() * 2f, 50f)
                    ContributorBubble(
                        contributor = c,
                        size = sz,
                        floatPhase = Random.nextFloat() * 6.28f,
                        floatAmpX = 4f + Random.nextFloat() * 8f,
                        floatAmpY = 4f + Random.nextFloat() * 8f,
                        floatSpeedX = 0.6f + Random.nextFloat() * 0.8f,
                        floatSpeedY = 0.7f + Random.nextFloat() * 0.9f)
                }
                isLoading = false

                // Load avatar images in parallel
                bubbles.forEach { bubble ->
                    launch(Dispatchers.IO) {
                        try {
                            val imgResponse = client.get(bubble.contributor.avatarUrl)
    val bytes = imgResponse.readBytes()
                            bubble.bitmap = bytes.decodeToImageBitmap()
                        } catch (e: Exception) {
                            Logger.e("Contributors", "Failed to load avatar for ${bubble.contributor.login}", e)
                        }
                    }
                }
            } else {
                error = "Failed to load contributors (${response.status})"
                isLoading = false
            }
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
            isLoading = false
            Logger.e("Contributors", "API error", e)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.92f)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )
                )
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Text(
                            stringResource(Res.string.contributorsLoading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("⚠️", fontSize = 48.sp)
                        error?.let { errorMsg ->
                            Text(
                                errorMsg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                else -> {
                    FloatingBubbleGrid(
                        bubbles = bubbles,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
                    ) { url ->
                        uriHandler.openUri(url)
                    }
                }
            }

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(Res.string.contributorsLabel),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (bubbles.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.contributorsCount, bubbles.size.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledIconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(Res.string.close),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Displays contributor bubbles in a scrollable wrapped grid layout,
 * each bubble gently floating with sinusoidal animation.
 */
@Composable
private fun FloatingBubbleGrid(
    bubbles: List<ContributorBubble>,
    modifier: Modifier = Modifier,
    onClick: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    // Global infinite transition driving the floating animation
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831853f, // 2*PI
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "floatTime"
    )

    // Fade-in for initial appearance
    val fadeAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeAnim.animateTo(1f, animationSpec = tween(600))
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryContainerColor = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainerColor = MaterialTheme.colorScheme.onSecondaryContainer
    val surfaceColor = MaterialTheme.colorScheme.surface

    // Use a scrollable FlowRow-like layout
    // Since FlowRow may not be available in all KMP targets, we use a Column of Rows
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .alpha(fadeAnim.value),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Wrap bubbles into rows dynamically
        // We use a simple approach: place items using FlowRow behavior via wrapping
        BubbleFlowLayout(bubbles = bubbles, time = time) { bubble, offsetX, offsetY ->
            ContributorBubbleItem(
                bubble = bubble,
                offsetX = offsetX,
                offsetY = offsetY,
                primaryColor = primaryColor,
                secondaryContainerColor = secondaryContainerColor,
                onSecondaryContainerColor = onSecondaryContainerColor,
                surfaceColor = surfaceColor,
                onClick = { onClick(bubble.contributor.htmlUrl) }
            )
        }
    }
}

/**
 * A custom flow layout that wraps bubbles into rows and applies floating offsets.
 */
@Composable
private fun BubbleFlowLayout(
    bubbles: List<ContributorBubble>,
    time: Float,
    content: @Composable (ContributorBubble, Float, Float) -> Unit
) {
    // We'll use a simple approach — arrange bubbles in rows of Arrangement
    // Each row is a horizontal arrangement of bubbles
    val rows = remember(bubbles) {
        // Create staggered rows: alternate between 3 and 4 items per row for visual interest
        val result = mutableListOf<List<ContributorBubble>>()
    var index = 0
        var rowSize = 3
        while (index < bubbles.size) {
            val end = min(index + rowSize, bubbles.size)
            result.add(bubbles.subList(index, end))
            index = end
            rowSize = if (rowSize == 3) 4 else 3  // Alternate
        }
        result
    }

    rows.forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            row.forEach { bubble ->
                val floatX = sin(time * bubble.floatSpeedX + bubble.floatPhase) * bubble.floatAmpX
                val floatY = sin(time * bubble.floatSpeedY + bubble.floatPhase + 1.5f) * bubble.floatAmpY
                content(bubble, floatX, floatY)
            }
        }
    }
}

@Composable
private fun ContributorBubbleItem(
    bubble: ContributorBubble,
    offsetX: Float,
    offsetY: Float,
    primaryColor: Color,
    secondaryContainerColor: Color,
    onSecondaryContainerColor: Color,
    surfaceColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .graphicsLayer {
                translationX = offsetX
                translationY = offsetY
            }
            .width(bubble.size.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(bubble.size.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(secondaryContainerColor)
                .border(
                    width = 2.5.dp,
                    color = primaryColor.copy(alpha = 0.25f),
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            bubble.bitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = bubble.contributor.login,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } ?: Text(
                bubble.contributor.login.take(1).uppercase(),
                fontSize = (bubble.size * 0.35f).sp,
                fontWeight = FontWeight.Bold,
                color = onSecondaryContainerColor
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Name label
        Text(
            text = bubble.contributor.login,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = onSecondaryContainerColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(
                    surfaceColor.copy(alpha = 0.75f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

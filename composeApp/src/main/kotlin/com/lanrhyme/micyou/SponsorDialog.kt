package com.lanrhyme.micyou

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.compose.ui.res.stringResource
@Serializable
private data class AifadianResponse(
    @SerialName("ec") val ec: Int = -1,
    @SerialName("em") val em: String = "",
    @SerialName("data") val data: AifadianData? = null
)

@Serializable
private data class AifadianData(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("total_page") val totalPage: Int = 0,
    @SerialName("page_size") val pageSize: Int = 100,
    @SerialName("page") val page: Int = 1,
    @SerialName("list") val list: List<AifadianSponsor> = emptyList()
)

@Serializable
private data class AifadianSponsor(
    @SerialName("user") val user: AifadianUser? = null,
    @SerialName("all_sum_amount") val allSumAmount: String = "0",
    @SerialName("create_time") val createTime: Int = 0,
    @SerialName("first_pay_time") val firstPayTime: Int = 0,
    @SerialName("current_plan") val currentPlan: AifadianPlan? = null
)

@Serializable
private data class AifadianUser(
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("avatar") val avatar: String = ""
)

@Serializable
private data class AifadianPlan(
    @SerialName("name") val name: String = ""
)

@Serializable
private data class AifadianRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("params") val params: String,
    @SerialName("ts") val ts: Long,
    @SerialName("sign") val sign: String
)

private class SponsorItem(
    val sponsor: AifadianSponsor,
    bitmap: ImageBitmap? = null
) {
    val userName: String get() = sponsor.user?.name ?: "Anonymous"
    val userAvatar: String get() = sponsor.user?.avatar ?: ""
    val userId: String get() = sponsor.user?.userId ?: ""
    val amount: String get() = sponsor.allSumAmount
    val timestamp: Int get() = if (sponsor.firstPayTime > 0) sponsor.firstPayTime else sponsor.createTime
    var bitmap by mutableStateOf(bitmap)
}

private fun buildSignedRequest(userId: String, apiToken: String, params: Map<String, String>): AifadianRequest {
    val ts = currentTimeSeconds()
    val paramsEntries = params.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
    val paramsJson = "{$paramsEntries}"
    val signStr = "${apiToken}params${paramsJson}ts${ts}user_id${userId}"
    val sign = md5(signStr)
    return AifadianRequest(userId = userId, params = paramsJson, ts = ts, sign = sign)
}

@Composable
fun SponsorsDialog(onDismiss: () -> Unit) {
    val client = remember {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { client.close() }
    }

    var sponsors by remember { mutableStateOf<List<SponsorItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val apiToken = getAifadianApiToken()
                val userId = getAifadianUserId()
                if (apiToken.isBlank() || userId.isBlank()) {
                    error = "API not configured"
                    isLoading = false
                    return@launch
                }

                val allSponsors = mutableListOf<AifadianSponsor>()
                var currentPage = 1
                var totalPages = 1

                while (currentPage <= totalPages) {
                    val request = buildSignedRequest(
                        userId = userId,
                        apiToken = apiToken,
                        params = mapOf("page" to currentPage.toString(), "per_page" to "100")
                    )

                    val response = client.post("https://afdian.com/api/open/query-sponsor") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }

                    if (response.status.isSuccess()) {
                        val result = response.body<AifadianResponse>()
                        if (result.ec == 200 && result.data != null) {
                            allSponsors.addAll(result.data.list)
                            totalPages = result.data.totalPage
                            currentPage++
                        } else {
                            error = result.em.ifBlank { "API error (ec=${result.ec})" }
                            isLoading = false
                            return@launch
                        }
                    } else {
                        error = "HTTP ${response.status}"
                        isLoading = false
                        return@launch
                    }
                }

                val sorted = allSponsors.sortedByDescending { if (it.firstPayTime > 0) it.firstPayTime else it.createTime }
                sponsors = sorted.map { SponsorItem(sponsor = it) }
                isLoading = false

                sponsors.forEach { item ->
                    launch(Dispatchers.IO) {
                        try {
                            if (item.userAvatar.isNotBlank()) {
                                val imgResponse = client.get(item.userAvatar)
                                val bytes = imgResponse.readBytes()
                                item.bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }
                        } catch (e: Exception) {
                            Logger.e("Sponsors", "Failed to load avatar for ${item.userName}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
                isLoading = false
                Logger.e("Sponsors", "API error", e)
            }
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
                .background(MaterialTheme.colorScheme.surface)
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
                            stringResource(R.string.sponsorsLoading),
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
                        Text("\u26A0\uFE0F", fontSize = 48.sp)
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
                sponsors.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("\u2764\uFE0F", fontSize = 48.sp)
                        Text(
                            stringResource(R.string.sponsorsEmpty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        stringResource(R.string.sponsorsLabel),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        String.format(stringResource(R.string.sponsorsCount), sponsors.size.toString()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledIconButton(
                                    onClick = onDismiss,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.close),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    stringResource(R.string.sponsorsDisclaimer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 80.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(sponsors, key = { it.userId.ifBlank { sponsors.indexOf(it).toString() } }) { item ->
                                    SponsorListItem(item)
                                }
                            }
                        }

                        FloatingActionButton(
                            onClick = { uriHandler.openUri("https://afdian.com/a/LanRhyme") },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(20.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = stringResource(R.string.sponsorsGoToAifadian),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SponsorListItem(item: SponsorItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                item.bitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = item.userName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } ?: Text(
                    item.userName.take(1).uppercase(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.userName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTimestamp(item.timestamp.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "\u00A5${item.amount}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return try {
        val instant = Instant.fromEpochSeconds(timestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${localDateTime.year}-${localDateTime.monthNumber.toString().padStart(2, '0')}-${localDateTime.dayOfMonth.toString().padStart(2, '0')}"
    } catch (e: Exception) {
        timestamp.toString()
    }
}

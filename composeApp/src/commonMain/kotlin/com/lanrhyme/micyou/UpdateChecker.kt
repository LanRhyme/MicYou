package com.lanrhyme.micyou

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long,
    @SerialName("content_type") val contentType: String = ""
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList()
)

data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val progress: Float = 0f
)

class UpdateChecker {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    private val downloadClient = HttpClient()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    suspend fun checkUpdate(): Result<GitHubRelease?> {
        val currentVersion = getAppVersion()
        if (currentVersion == "dev") return Result.success(null)

        return try {
            val apiResponse = client.get("https://api.github.com/repos/LanRhyme/MicYou/releases/latest") {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            
            if (apiResponse.status.isSuccess()) {
                val latestRelease: GitHubRelease = apiResponse.body()
                val latestVersion = latestRelease.tagName.removePrefix("v")
                if (isNewerVersion(currentVersion, latestVersion)) {
                    return Result.success(latestRelease)
                }
                return Result.success(null)
            }
            
            if (apiResponse.status == HttpStatusCode.Forbidden || apiResponse.status == HttpStatusCode.TooManyRequests) {
                Logger.w("UpdateChecker", "GitHub API rate limited, trying website fallback...")
                return checkUpdateViaWebsite(currentVersion, "New version released")
            }

            Result.failure(Exception("HTTP Error: ${apiResponse.status.value}"))
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "API check failed, trying fallback...", e)
            return checkUpdateViaWebsite(currentVersion, "New version released")
        }
    }

    suspend fun downloadUpdate(downloadUrl: String, targetPath: String): Result<String> {
        _downloadProgress.value = DownloadProgress()
        return try {
            downloadClient.prepareGet(downloadUrl) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            }.execute { response ->
                val totalBytes = response.contentLength() ?: 0L
                var downloadedBytes = 0L
                val channel: ByteReadChannel = response.body()
                val buffer = ByteArray(8192)

                writeToFile(targetPath) { writeChunk ->
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead <= 0) break
                        writeChunk(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        _downloadProgress.value = DownloadProgress(downloadedBytes, totalBytes, progress)
                    }
                }

                Logger.i("UpdateChecker", "Download completed: $targetPath ($downloadedBytes bytes)")
                Result.success(targetPath)
            }
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "Download failed", e)
            _downloadProgress.value = DownloadProgress()
            Result.failure(e)
        }
    }

    fun findAssetForPlatform(release: GitHubRelease): GitHubAsset? {
        return findPlatformAsset(release.assets)
    }

    private suspend fun checkUpdateViaWebsite(currentVersion: String, releaseBody: String): Result<GitHubRelease?> {
        return try {
            val response = client.get("https://github.com/LanRhyme/MicYou/releases/latest") {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            }
            
            val finalUrl = response.call.request.url.toString()
            
            if (finalUrl.contains("/tag/")) {
                val tag = finalUrl.substringAfterLast("/")
                val latestVersion = tag.removePrefix("v")
                
                if (isNewerVersion(currentVersion, latestVersion)) {
                    return Result.success(GitHubRelease(
                        tagName = tag,
                        htmlUrl = finalUrl,
                        body = releaseBody
                    ))
                }
            }
            Result.success(null)
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "Website fallback also failed", e)
            Result.failure(e)
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        fun parseParts(v: String) = v.split(".")
            .map { it.substringBefore("-") }
            .mapNotNull { it.toIntOrNull() }

        val currentParts = parseParts(current.removePrefix("v"))
        val latestParts = parseParts(latest.removePrefix("v"))

        val size = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until size) {
            val curr = currentParts.getOrNull(i) ?: 0
            val late = latestParts.getOrNull(i) ?: 0
            if (late > curr) return true
            if (late < curr) return false
        }
        return false
    }
}

// Platform-specific: write downloaded bytes to file
expect suspend fun writeToFile(path: String, writer: suspend ((ByteArray, Int, Int) -> Unit) -> Unit)

// Platform-specific: find the right asset for the current platform
expect fun findPlatformAsset(assets: List<GitHubAsset>): GitHubAsset?

// Platform-specific: get the download directory for updates
expect fun getUpdateDownloadPath(fileName: String): String

// Platform-specific: install the downloaded update file
expect fun installUpdate(filePath: String)

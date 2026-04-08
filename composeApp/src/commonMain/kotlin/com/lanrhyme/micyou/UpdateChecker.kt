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

// MirrorChyan API response
@Serializable
data class MirrorChyanResponse(
    @SerialName("code") val code: Int,
    @SerialName("msg") val msg: String,
    @SerialName("data") val data: MirrorChyanData? = null
)

@Serializable
data class MirrorChyanData(
    @SerialName("version_name") val versionName: String,
    @SerialName("version_number") val versionNumber: Int,
    @SerialName("url") val url: String? = null,
    @SerialName("filesize") val filesize: Long? = null,
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("update_type") val updateType: String? = null, // incremental | full
    @SerialName("os") val os: String? = null,
    @SerialName("arch") val arch: String? = null,
    @SerialName("channel") val channel: String? = null, // stable | beta | alpha
    @SerialName("release_note") val releaseNote: String,
    @SerialName("cdk_expired_time") val cdkExpiredTime: Long? = null
)

// Combined update info
data class UpdateInfo(
    val versionName: String,
    val releaseNote: String,
    val mirrorUrl: String? = null, // MirrorChyan download URL
    val mirrorFilesize: Long? = null,
    val mirrorSha256: String? = null,
    val mirrorUpdateType: String? = null,
    val cdkExpiredTime: Long? = null,
    val githubRelease: GitHubRelease? = null,
    val isLatest: Boolean = false
)

data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val progress: Float = 0f
)

class UpdateChecker {
    companion object {
        const val MIRROR_RID = "MicYou"
        const val MIRROR_API_BASE = "https://mirrorchyan.com/api"
        private const val GITHUB_RELEASE_API = "https://api.github.com/repos/LanRhyme/MicYou/releases/latest"
        private const val GITHUB_RELEASE_WEB = "https://github.com/LanRhyme/MicYou/releases/latest"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

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

    suspend fun checkUpdate(cdk: String? = null): Result<UpdateInfo?> {
        val currentVersion = getAppVersion()
        if (currentVersion == "dev") return Result.success(null)

        val mirrorInfo = cdk
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { mirrorCdk -> checkUpdateViaMirror(currentVersion, mirrorCdk).getOrNull() }

        if (mirrorInfo?.mirrorUrl != null) {
            return Result.success(mirrorInfo)
        }

        return checkUpdateViaGitHub(currentVersion)
    }

    private suspend fun checkUpdateViaMirror(currentVersion: String, cdk: String): Result<UpdateInfo?> {
        return try {
            val os = getMirrorOs()
            val arch = getMirrorArch()
            val userAgent = "MicYou_${getPlatformName()}"

            val response = client.get("$MIRROR_API_BASE/resources/$MIRROR_RID/latest") {
                parameter("current_version", "v$currentVersion")
                parameter("cdk", cdk)
                parameter("user_agent", userAgent)
                parameter("os", os)
                parameter("arch", arch)
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("HTTP Error: ${response.status.value}"))
            }

            val mirrorResponse: MirrorChyanResponse = response.body()
            val data = mirrorResponse.data

            if (mirrorResponse.code != 0 || data == null) {
                Logger.w("UpdateChecker", "MirrorChyan error: code=${mirrorResponse.code}, msg=${mirrorResponse.msg}")
                return Result.failure(Exception(mirrorResponse.msg))
            }

            val latestVersion = data.versionName.removePrefix("v")
            val isLatest = !isNewerVersion(currentVersion, latestVersion)
            Result.success(data.toUpdateInfo(isLatest))
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "MirrorChyan check failed", e)
            Result.failure(e)
        }
    }

    private suspend fun checkUpdateViaGitHub(currentVersion: String): Result<UpdateInfo?> {
        return try {
            val apiResponse = client.get(GITHUB_RELEASE_API) {
                header(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }

            if (apiResponse.status.isSuccess()) {
                val latestRelease: GitHubRelease = apiResponse.body()
                val latestVersion = latestRelease.tagName.removePrefix("v")
                if (isNewerVersion(currentVersion, latestVersion)) {
                    return Result.success(latestRelease.toUpdateInfo())
                }
                return Result.success(null)
            }

            if (apiResponse.status == HttpStatusCode.Forbidden || apiResponse.status == HttpStatusCode.TooManyRequests) {
                Logger.w("UpdateChecker", "GitHub API rate limited, trying website fallback...")
                return checkUpdateViaWebsiteFallback(currentVersion)
            }

            Result.failure(Exception("HTTP Error: ${apiResponse.status.value}"))
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "API check failed, trying fallback...", e)
            return checkUpdateViaWebsiteFallback(currentVersion)
        }
    }

    private suspend fun checkUpdateViaWebsiteFallback(currentVersion: String): Result<UpdateInfo?> {
        return try {
            val response = client.get(GITHUB_RELEASE_WEB) {
                header(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
            }

            val finalUrl = response.call.request.url.toString()

            if (finalUrl.contains("/tag/")) {
                val tag = finalUrl.substringAfterLast("/")
                val latestVersion = tag.removePrefix("v")

                if (isNewerVersion(currentVersion, latestVersion)) {
                    return Result.success(UpdateInfo(
                        versionName = tag,
                        releaseNote = "New version released",
                        githubRelease = GitHubRelease(
                            tagName = tag,
                            htmlUrl = finalUrl,
                            body = "New version released"
                        ),
                        isLatest = false
                    ))
                }
            }
            Result.success(null)
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "Website fallback also failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadUpdate(downloadUrl: String, targetPath: String): Result<String> {
        _downloadProgress.value = DownloadProgress()
        Logger.i("UpdateChecker", "Downloading from: $downloadUrl")
        return try {
            downloadClient.prepareGet(downloadUrl) {
                header(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
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

    private fun MirrorChyanData.toUpdateInfo(isLatest: Boolean): UpdateInfo {
        return UpdateInfo(
            versionName = versionName,
            releaseNote = releaseNote,
            mirrorUrl = if (isLatest) null else url,
            mirrorFilesize = if (isLatest) null else filesize,
            mirrorSha256 = if (isLatest) null else sha256,
            mirrorUpdateType = if (isLatest) null else updateType,
            cdkExpiredTime = cdkExpiredTime,
            isLatest = isLatest
        )
    }

    private fun GitHubRelease.toUpdateInfo(): UpdateInfo {
        return UpdateInfo(
            versionName = tagName,
            releaseNote = body,
            githubRelease = this,
            isLatest = false
        )
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

// Platform-specific: get OS string for MirrorChyan API (windows/linux/darwin/android)
expect fun getMirrorOs(): String

// Platform-specific: get architecture string for MirrorChyan API (386/amd64/arm/arm64)
expect fun getMirrorArch(): String

// Platform-specific: get platform name for user_agent
expect fun getPlatformName(): String

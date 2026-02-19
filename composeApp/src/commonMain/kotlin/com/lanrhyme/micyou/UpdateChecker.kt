package com.lanrhyme.micyou

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String
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

    suspend fun checkUpdate(): GitHubRelease? {
        return try {
            val currentVersion = getAppVersion()
            if (currentVersion == "dev") return null

            val latestRelease: GitHubRelease = client.get("https://api.github.com/repos/LanRhyme/MicYou/releases/latest").body()
            
            val latestVersion = latestRelease.tagName.removePrefix("v")
            if (isNewerVersion(currentVersion, latestVersion)) {
                latestRelease
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e("UpdateChecker", "Failed to check for updates", e)
            null
        }
    }

}

internal fun isNewerVersion(current: String, latest: String): Boolean {
    val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
    val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

    val size = maxOf(currentParts.size, latestParts.size)
    for (i in 0 until size) {
        val curr = currentParts.getOrNull(i) ?: 0
        val late = latestParts.getOrNull(i) ?: 0
        if (late > curr) return true
        if (late < curr) return false
    }
    return false
}

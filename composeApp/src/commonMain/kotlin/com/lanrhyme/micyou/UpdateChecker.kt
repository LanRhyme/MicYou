package com.lanrhyme.micyou

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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

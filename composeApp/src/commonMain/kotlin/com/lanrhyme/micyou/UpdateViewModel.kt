package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val updateInfo: UpdateInfo? = null,
    val updateDownloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    val updateDownloadProgress: Float = 0f,
    val updateDownloadedBytes: Long = 0,
    val updateTotalBytes: Long = 0,
    val updateErrorMessage: String? = null
)

class UpdateViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private val updateChecker = UpdateChecker()
    private val settings = SettingsFactory.getSettings()

    init {
        // Collect download progress from UpdateChecker
        viewModelScope.launch {
            updateChecker.downloadProgress.collect { progress ->
                _uiState.update {
                    it.copy(
                        updateDownloadProgress = progress.progress,
                        updateDownloadedBytes = progress.downloadedBytes,
                        updateTotalBytes = progress.totalBytes
                    )
                }
            }
        }
    }

    fun checkUpdateManual() {
        viewModelScope.launch {
            checkUpdateInternal()
        }
    }

    fun checkUpdateAuto() {
        if (!settings.getBoolean("auto_check_update", true)) {
            return
        }

        viewModelScope.launch {
            checkUpdateInternal()
        }
    }

    fun downloadAndInstallUpdate(useMirror: Boolean) {
        val info = _uiState.value.updateInfo ?: return

        val downloadTarget = resolveDownloadTarget(info, useMirror) ?: run {
            failDownload("No download source available")
            return
        }

        _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloading, updateErrorMessage = null) }

        viewModelScope.launch {
            val result = updateChecker.downloadUpdate(downloadTarget.downloadUrl, downloadTarget.targetPath)

            result.onSuccess { filePath ->
                _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Installing) }
                try {
                    installUpdate(filePath)
                } catch (e: Exception) {
                    Logger.e("UpdateViewModel", "Install failed", e)
                    failDownload(e.message)
                }
            }.onFailure { e ->
                failDownload(e.message)
            }
        }
    }

    private suspend fun checkUpdateInternal() {
        val cdk = settings.getString("mirror_cdk", "")
        val result = updateChecker.checkUpdate(cdk)

        result.onSuccess { info ->
            if (info != null && !info.isLatest) {
                _uiState.update {
                    it.copy(updateInfo = info)
                }
            }
        }
    }

    private fun resolveDownloadTarget(info: UpdateInfo, useMirror: Boolean): DownloadTarget? {
        if (useMirror && info.mirrorUrl != null) {
            val fileNameFromUrl = extractMirrorFileName(info.mirrorUrl)
            val extension = extractFileExtension(fileNameFromUrl)
                ?: extractFileExtensionFromMirrorUrl(info.mirrorUrl)

            val fileName = fileNameFromUrl
                ?: buildFallbackMirrorFileName(info, extension)

            return DownloadTarget(
                downloadUrl = info.mirrorUrl,
                targetPath = getUpdateDownloadPath(fileName)
            )
        }

        val release = info.githubRelease ?: return null
        val asset = updateChecker.findAssetForPlatform(release) ?: run {
            openUrl(release.htmlUrl)
            dismissUpdateDialog()
            return null
        }

        return DownloadTarget(
            downloadUrl = asset.browserDownloadUrl,
            targetPath = getUpdateDownloadPath(asset.name)
        )
    }

    private fun failDownload(errorMessage: String?) {
        _uiState.update {
            it.copy(
                updateDownloadState = UpdateDownloadState.Failed,
                updateErrorMessage = errorMessage
            )
        }
    }

    private fun extractMirrorFileName(url: String): String? {
        val pathFileName = url
            .substringBefore("?")
            .substringAfterLast("/")
            .trim()

        return pathFileName.takeIf { it.isNotBlank() }
    }

    private fun extractFileExtension(fileName: String?): String? {
        val ext = fileName
            ?.substringAfterLast(".", "")
            ?.trim()
            ?.trimStart('.')
            ?.lowercase()

        return ext?.takeIf { it.isNotBlank() }
    }

    private fun extractFileExtensionFromMirrorUrl(url: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return null

        val filenameFromQuery = query
            .split("&")
            .asSequence()
            .mapNotNull { segment ->
                val parts = segment.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val key = parts[0].lowercase()
                if (key != "filename" && key != "file" && key != "name") return@mapNotNull null
                parts[1].substringAfterLast('/').trim()
            }
            .firstOrNull()

        return extractFileExtension(filenameFromQuery)
    }

    private fun buildFallbackMirrorFileName(info: UpdateInfo, extension: String?): String {
        val extSuffix = extension
            ?.takeIf { it.isNotBlank() }
            ?.let { ".${it}" }
            .orEmpty()

        return "MicYou-${info.versionName}-${getMirrorOs()}-${getMirrorArch()}$extSuffix"
    }

    fun dismissUpdateDialog() {
        _uiState.update {
            it.copy(
                updateInfo = null,
                updateDownloadState = UpdateDownloadState.Idle,
                updateDownloadProgress = 0f,
                updateErrorMessage = null
            )
        }
    }

    fun openGitHubRelease() {
        val info = _uiState.value.updateInfo
        if (info?.githubRelease != null) {
            openUrl(info.githubRelease.htmlUrl)
        } else {
            openUrl("https://github.com/LanRhyme/MicYou/releases/latest")
        }
        dismissUpdateDialog()
    }
}

private data class DownloadTarget(
    val downloadUrl: String,
    val targetPath: String
)

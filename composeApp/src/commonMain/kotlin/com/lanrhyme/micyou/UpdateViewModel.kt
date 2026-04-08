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
        viewModelScope.launch { checkUpdateInternal() }
    }

    fun checkUpdateAuto() {
        if (settings.getBoolean("auto_check_update", true)) checkUpdateManual()
    }

    fun downloadAndInstallUpdate(useMirror: Boolean) {
        val info = _uiState.value.updateInfo ?: return
        if (isPortableApp()) {
            openGitHubRelease()
            return
        }

        val target = resolveDownloadTarget(info, useMirror) ?: return failDownload("No download source available")

        _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloading, updateErrorMessage = null) }

        viewModelScope.launch {
            updateChecker.downloadUpdate(target.downloadUrl, target.targetPath)
                .onSuccess {
                    _uiState.update { state -> state.copy(updateDownloadState = UpdateDownloadState.Installing) }
                    try { installUpdate(it) } catch (e: Exception) {
                        Logger.e("UpdateViewModel", "Install failed", e)
                        failDownload(e.message)
                    }
                }.onFailure { failDownload(it.message) }
        }
    }

    private suspend fun checkUpdateInternal() {
        val cdk = settings.getString("mirror_cdk", "")
        updateChecker.checkUpdate(cdk).onSuccess { info ->
            if (info != null && !info.isLatest) _uiState.update { it.copy(updateInfo = info) }
        }
    }

    private fun resolveDownloadTarget(info: UpdateInfo, useMirror: Boolean): DownloadTarget? {
        val asset = info.githubRelease?.let { updateChecker.findAssetForPlatform(it) }

        if (useMirror && info.mirrorUrl != null) {
            val url = info.mirrorUrl
            val qName = Regex("(?i)[?&](?:filename|file|name)=([^&]+)").find(url)?.groupValues?.get(1)?.substringAfterLast("/")
            val pName = url.substringBefore("?").substringAfterLast("/").takeIf { it.contains(".") }
            
            val ext = qName?.substringAfterLast(".", "")?.takeIf { it.isNotBlank() }
                ?: pName?.substringAfterLast(".", "")?.takeIf { it.isNotBlank() }
                ?: asset?.name?.substringAfterLast(".", "")?.takeIf { it.isNotBlank() }
                ?: when (getMirrorOs()) { "windows" -> "exe"; "darwin" -> "dmg"; else -> "deb" }
                
            val name = pName ?: qName?.takeIf { it.contains(".") } ?: "MicYou-${info.versionName}-${getMirrorOs()}-${getMirrorArch()}.$ext"

            return DownloadTarget(url, getUpdateDownloadPath(name))
        }

        return asset?.let {
            DownloadTarget(it.browserDownloadUrl, getUpdateDownloadPath(it.name))
        } ?: run {
            info.githubRelease?.htmlUrl?.let { openUrl(it) }
            dismissUpdateDialog()
            null
        }
    }

    private fun failDownload(errorMessage: String?) {
        _uiState.update {
            it.copy(
                updateDownloadState = UpdateDownloadState.Failed,
                updateErrorMessage = errorMessage
            )
        }
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

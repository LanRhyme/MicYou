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

    fun checkUpdateManual(language: AppLanguage) {
        viewModelScope.launch {
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
    }

    fun checkUpdateAuto() {
        val autoCheckUpdate = settings.getBoolean("auto_check_update", true)
        if (autoCheckUpdate) {
            viewModelScope.launch {
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
        }
    }

    fun downloadAndInstallUpdate(useMirror: Boolean) {
        val info = _uiState.value.updateInfo ?: return

        _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloading, updateErrorMessage = null) }

        viewModelScope.launch {
            val downloadUrl: String?
            val targetPath: String?

            if (useMirror && info.mirrorUrl != null) {
                // Use MirrorChyan download
                val fileName = "MicYou-${info.versionName}-${getMirrorOs()}-${getMirrorArch()}.exe"
                downloadUrl = info.mirrorUrl
                targetPath = getUpdateDownloadPath(fileName)
            } else if (info.githubRelease != null) {
                // Use GitHub download
                val asset = updateChecker.findAssetForPlatform(info.githubRelease)
                if (asset == null) {
                    openUrl(info.githubRelease.htmlUrl)
                    dismissUpdateDialog()
                    return@launch
                }
                downloadUrl = asset.browserDownloadUrl
                targetPath = getUpdateDownloadPath(asset.name)
            } else {
                _uiState.update {
                    it.copy(
                        updateDownloadState = UpdateDownloadState.Failed,
                        updateErrorMessage = "No download source available"
                    )
                }
                return@launch
            }

            val result = updateChecker.downloadUpdate(downloadUrl, targetPath)

            result.onSuccess { filePath ->
                _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloaded) }
                _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Installing) }
                try {
                    installUpdate(filePath)
                } catch (e: Exception) {
                    Logger.e("UpdateViewModel", "Install failed", e)
                    _uiState.update {
                        it.copy(
                            updateDownloadState = UpdateDownloadState.Failed,
                            updateErrorMessage = e.message
                        )
                    }
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        updateDownloadState = UpdateDownloadState.Failed,
                        updateErrorMessage = e.message
                    )
                }
            }
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

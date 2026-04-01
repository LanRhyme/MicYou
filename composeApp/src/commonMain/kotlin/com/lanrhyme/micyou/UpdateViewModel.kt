package com.lanrhyme.micyou

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val newVersionAvailable: GitHubRelease? = null,
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
            val strings = getStrings(language)
            // Note: snackbar message should be handled by the calling ViewModel
            val result = updateChecker.checkUpdate()
            
            result.onSuccess { release ->
                if (release != null) {
                    _uiState.update { it.copy(newVersionAvailable = release) }
                }
            }
        }
    }

    fun checkUpdateAuto() {
        val autoCheckUpdate = settings.getBoolean("auto_check_update", true)
        if (autoCheckUpdate) {
            viewModelScope.launch {
                val result = updateChecker.checkUpdate()
                result.onSuccess { release ->
                    if (release != null) {
                        _uiState.update { it.copy(newVersionAvailable = release) }
                    }
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val release = _uiState.value.newVersionAvailable ?: return
        val asset = updateChecker.findAssetForPlatform(release)
        if (asset == null) {
            // No matching asset - fall back to opening the release page
            openUrl(release.htmlUrl)
            dismissUpdateDialog()
            return
        }

        _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloading, updateErrorMessage = null) }

        viewModelScope.launch {
            val targetPath = getUpdateDownloadPath(asset.name)
            val useMirror = settings.getBoolean("use_mirror_download", false)
            val result = updateChecker.downloadUpdate(asset.browserDownloadUrl, targetPath, useMirror)

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
                newVersionAvailable = null,
                updateDownloadState = UpdateDownloadState.Idle,
                updateDownloadProgress = 0f,
                updateErrorMessage = null
            )
        }
    }
}

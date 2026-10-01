/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.lanrhyme.micyou.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.lanrhyme.micyou.settings.Settings
import com.lanrhyme.micyou.settings.SettingsFactory
import com.lanrhyme.micyou.util.openUrl
import com.lanrhyme.micyou.viewmodel.UpdateDownloadState
data class UpdateUiState(
    val updateInfo: UpdateInfo? = null,
    val updateDownloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    val updateDownloadProgress: Float = 0f,
    val updateDownloadedBytes: Long = 0,
    val updateTotalBytes: Long = 0,
    val updateErrorMessage: String? = null
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    data object NoUpdate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class UpdateViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    private val updateChecker = UpdateChecker()
    private val settings = SettingsFactory.getSettings()
    private val closed = AtomicBoolean(false)

    private val _checkResultFlow = MutableStateFlow<UpdateCheckResult?>(null)
    val checkResultFlow: StateFlow<UpdateCheckResult?> = _checkResultFlow.asStateFlow()

    init {
        viewModelScope.launch {
            updateChecker.downloadProgress.collect { p ->
                _uiState.update { it.copy(updateDownloadProgress = p.progress, updateDownloadedBytes = p.downloadedBytes, updateTotalBytes = p.totalBytes) }
            }
        }
    }

    fun checkUpdateManual() {
        viewModelScope.launch {
            _checkResultFlow.emit(null)
    val result = checkUpdateInternal()
            _checkResultFlow.emit(result)
        }
    }

    fun checkUpdateAuto() {
        if (settings.getBoolean("auto_check_update", true)) {
            viewModelScope.launch { checkUpdateInternal() }
        }
    }

    fun downloadAndInstallUpdate(useMirror: Boolean) {
        val info = _uiState.value.updateInfo ?: return
        if (isPortableApp()) return openGitHubRelease()
    val targetUrl = if (useMirror) info.mirrorUrl else info.githubRelease?.let { updateChecker.findAssetForPlatform(it)?.browserDownloadUrl }
        if (targetUrl == null) {
            info.githubRelease?.htmlUrl?.let { openUrl(it) }
            return dismissUpdateDialog()
        }
    val qName = Regex("(?i)[?&](?:filename|file|name)=([^&]+)").find(targetUrl)?.groupValues?.get(1)?.substringAfterLast("/")
    val pName = targetUrl.substringBefore("?").substringAfterLast("/").takeIf { it.contains(".") }
    val ext = pName?.substringAfterLast(".", "") ?: qName?.substringAfterLast(".", "") ?: info.githubRelease?.let { updateChecker.findAssetForPlatform(it)?.name }?.substringAfterLast(".", "") ?: when(getMirrorOs()) { "windows" -> "exe"; "darwin" -> "dmg"; else -> "deb" }
    val name = pName ?: qName?.takeIf { it.contains(".") } ?: "MicYou-${info.versionName}-${getMirrorOs()}-${getMirrorArch()}.${ext.takeIf { it.isNotBlank() } ?: "exe"}"

        _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloading, updateErrorMessage = null) }
        viewModelScope.launch {
            updateChecker.downloadUpdate(targetUrl, getUpdateDownloadPath(name)).onSuccess {
                _uiState.update { s -> s.copy(updateDownloadState = UpdateDownloadState.Installing) }
                runCatching { installUpdate(it) }.onFailure { e -> failDownload(e.message) }
            }.onFailure { failDownload(it.message) }
        }
    }

    private suspend fun checkUpdateInternal(): UpdateCheckResult {
        return updateChecker.checkUpdate(settings.getString("mirror_cdk", "")).fold(
            onSuccess = { info ->
                if (info?.isLatest == false) {
                    _uiState.update { it.copy(updateInfo = info) }
                    UpdateCheckResult.UpdateAvailable(info)
                } else {
                    UpdateCheckResult.NoUpdate
                }
            },
            onFailure = { e ->
                UpdateCheckResult.Error(e.message ?: "Unknown error")
            }
        )
    }

    private fun failDownload(error: String?) = _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Failed, updateErrorMessage = error) }

    fun dismissUpdateDialog() = _uiState.update { UpdateUiState() }

    fun openGitHubRelease() {
        openUrl(_uiState.value.updateInfo?.githubRelease?.htmlUrl ?: "https://github.com/MicYou-Dev/MicYou/releases/latest")
        dismissUpdateDialog()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) viewModelScope.cancel()
    }
}